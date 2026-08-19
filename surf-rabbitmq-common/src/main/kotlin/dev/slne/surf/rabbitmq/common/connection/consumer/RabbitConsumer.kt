package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.*
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionListener
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import kotlinx.coroutines.*
import java.lang.AutoCloseable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RabbitConsumer(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {

    private data class ConsumerRegistration(
        val queue: String,
        val autoAck: Boolean,
        val prefetchCount: Int,
        val requeueOnHandlerError: Boolean,
        val handlerTimeout: Duration?,
        val onCancelled: (consumerTag: String) -> Unit,
        val handler: suspend (
            consumerTag: String,
            message: Delivery,
            ack: RabbitAck
        ) -> Unit
    )

    companion object {
        private val log = logger()
    }

    private val channelDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "rabbit-consumer-channel-${connectionProvider.connectionName}-$name"
            ).apply {
                isDaemon = true
            }
        }
        .asCoroutineDispatcher()

    private val channelScope = CoroutineScope(
        SupervisorJob() + channelDispatcher
    )

    private val processingScope = CoroutineScope(
        SupervisorJob() + processingDispatcher
    )

    private val activeDeliveries = ConcurrentHashMap.newKeySet<Job>()

    private var channel: Channel? = null
    private var registration: ConsumerRegistration? = null
    private var recoveringConsumer = false

    @Volatile
    private var closed = false

    private val connectionListener = object : RabbitConnectionListener {
        override fun onConnectionLost(cause: ShutdownSignalException) {
            cancelActiveDeliveries(
                message = "RabbitMQ connection was lost",
                cause = cause
            )
        }
    }

    init {
        connectionProvider.addListener(connectionListener)
    }

    /**
     * @see Channel.queueDeclare
     */
    suspend fun declareQueue(
        queue: String,
        durable: Boolean = true,
        exclusive: Boolean = false,
        autoDelete: Boolean = false,
        arguments: Map<String, Any>? = null
    ): AMQP.Queue.DeclareOk = withContext(channelDispatcher) {
        getChannel().queueDeclare(
            queue,
            durable,
            exclusive,
            autoDelete,
            arguments
        )
    }

    /**
     * @see Channel.exchangeDeclare
     */
    suspend fun declareExchange(
        exchange: String,
        type: BuiltinExchangeType = BuiltinExchangeType.TOPIC,
        durable: Boolean = true
    ): AMQP.Exchange.DeclareOk = withContext(channelDispatcher) {
        getChannel().exchangeDeclare(exchange, type, durable)
    }

    /**
     * @see Channel.queueBind
     */
    suspend fun bindQueue(
        queue: String,
        exchange: String,
        routingKey: String
    ): AMQP.Queue.BindOk = withContext(channelDispatcher) {
        getChannel().queueBind(queue, exchange, routingKey)
    }

    /**
     * @see Channel.basicConsume
     */
    suspend fun consume(
        queue: String,
        autoAck: Boolean = false,
        prefetchCount: Int = 10,
        requeueOnHandlerError: Boolean = true,
        handlerTimeout: Duration? = null,
        onCancelled: (consumerTag: String) -> Unit = {},
        handler: suspend (
            consumerTag: String,
            message: Delivery,
            ack: RabbitAck
        ) -> Unit
    ): String = withContext(channelDispatcher) {
        check(registration == null) {
            "RabbitConsumer '$name' already has a registered consumer"
        }

        val registration = ConsumerRegistration(
            queue = queue,
            autoAck = autoAck,
            prefetchCount = prefetchCount,
            requeueOnHandlerError = requeueOnHandlerError,
            handlerTimeout = handlerTimeout,
            onCancelled = onCancelled,
            handler = handler
        )

        this@RabbitConsumer.registration = registration

        try {
            startConsumer(registration)
        } catch (cause: Throwable) {
            this@RabbitConsumer.registration = null
            throw cause
        }
    }

    suspend fun cancel(consumerTag: String) {
        withContext(channelDispatcher) {
            getChannel().basicCancel(consumerTag)
            registration = null
        }
    }

    private suspend fun startConsumer(
        registration: ConsumerRegistration
    ): String {
        val consumerChannel = getChannel()

        if (
            !registration.autoAck &&
            registration.prefetchCount > 0
        ) {
            consumerChannel.basicQos(registration.prefetchCount)
        }

        val deliverCallback = DeliverCallback { consumerTag, message ->
            launchDelivery(
                registration = registration,
                consumerChannel = consumerChannel,
                consumerTag = consumerTag,
                message = message
            )
        }

        val cancelCallback = CancelCallback { consumerTag ->
            registration.onCancelled(consumerTag)
        }

        return consumerChannel.basicConsume(
            registration.queue,
            registration.autoAck,
            deliverCallback,
            cancelCallback
        )
    }

    private fun launchDelivery(
        registration: ConsumerRegistration,
        consumerChannel: Channel,
        consumerTag: String,
        message: Delivery
    ) {
        val ack = RabbitAck(
            channelDispatcher = channelDispatcher,
            channel = consumerChannel,
            deliveryTag = message.envelope.deliveryTag,
            enabled = !registration.autoAck,
            onChannelFailure = { cause ->
                scheduleChannelRecovery(
                    failedChannel = consumerChannel,
                    cause = cause
                )
            }
        )

        val job = processingScope.launch(
            start = CoroutineStart.LAZY
        ) {
            try {
                val timeout = registration.handlerTimeout

                if (timeout == null) {
                    registration.handler(
                        consumerTag,
                        message,
                        ack
                    )
                } else {
                    withTimeout(timeout) {
                        registration.handler(
                            consumerTag,
                            message,
                            ack
                        )
                    }
                }
            } catch (cause: Throwable) {
                if (
                    cause is CancellationException &&
                    !currentCoroutineContext().isActive
                ) {
                    throw cause
                }

                ack.nackIfUnsettled(
                    requeue = registration.requeueOnHandlerError
                )
            }
        }

        activeDeliveries += job

        job.invokeOnCompletion {
            activeDeliveries -= job
        }

        job.start()
    }

    private suspend fun getChannel(): Channel {
        val connectionGeneration = connectionProvider.awaitOpen()

        val existing = channel
        if (existing != null && existing.isOpen) {
            return existing
        }

        return connectionProvider
            .createChannel(connectionGeneration)
            .also { created ->
                installChannelShutdownListener(created)
                channel = created
            }
    }

    private fun installChannelShutdownListener(created: Channel) {
        created.addShutdownListener { cause ->
            if (
                closed ||
                cause.isInitiatedByApplication
            ) {
                return@addShutdownListener
            }

            cancelActiveDeliveries(
                message = "RabbitMQ consumer channel was closed",
                cause = cause
            )

            if (!cause.isHardError) {
                scheduleChannelRecovery(created, cause)
            }
        }
    }

    private fun scheduleChannelRecovery(
        failedChannel: Channel,
        cause: Throwable
    ) {
        channelScope.launch {
            if (closed || channel !== failedChannel) {
                return@launch
            }

            channel = null

            if (recoveringConsumer) {
                return@launch
            }

            recoveringConsumer = true

            try {
                cancelActiveDeliveries(
                    message = "RabbitMQ consumer channel became unusable",
                    cause = cause
                )

                runCatching {
                    failedChannel.abort()
                }

                log.atWarning()
                    .withCause(cause)
                    .log(
                        "RabbitMQ consumer channel '%s/%s' was closed by a channel-level error; recreating the consumer",
                        connectionProvider.connectionName,
                        name
                    )

                while (isActive && !closed) {
                    val currentRegistration = registration
                        ?: return@launch

                    try {
                        connectionProvider.awaitOpen()
                        startConsumer(currentRegistration)

                        log.atInfo()
                            .log(
                                "RabbitMQ consumer '%s/%s' was restored on a new channel",
                                connectionProvider.connectionName,
                                name
                            )

                        return@launch
                    } catch (recoveryCause: CancellationException) {
                        throw recoveryCause
                    } catch (recoveryCause: Throwable) {
                        log.atWarning()
                            .withCause(recoveryCause)
                            .log(
                                "Could not restore RabbitMQ consumer '%s/%s'; retrying",
                                connectionProvider.connectionName,
                                name
                            )

                        delay(2.seconds)
                    }
                }
            } finally {
                recoveringConsumer = false
            }
        }
    }

    private fun cancelActiveDeliveries(
        message: String,
        cause: Throwable
    ) {
        activeDeliveries.forEach { job ->
            val cancellation = CancellationException(message)
            cancellation.initCause(cause)
            job.cancel(cancellation)
        }
    }

    private fun resetChannel() {
        val current = channel
        channel = null

        runCatching {
            current?.close()
        }
    }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        if (closed) {
            return
        }

        closed = true
        connectionProvider.removeListener(connectionListener)

        cancelActiveDeliveries(
            message = "RabbitMQ consumer is closing",
            cause = CancellationException("Consumer closed")
        )

        processingScope.cancel()
        channelScope.cancel()

        try {
            runBlocking(channelDispatcher) {
                registration = null
                resetChannel()
            }
        } finally {
            channelDispatcher.close()
        }
    }
}