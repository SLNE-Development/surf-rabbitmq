package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.*
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionClosedException
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import kotlinx.coroutines.*
import java.lang.AutoCloseable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RabbitConsumer(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {
    companion object {
        private val log = logger()
    }

    private val channelThread = AtomicReference<Thread>()

    private val channelDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "rabbit-consumer-channel-${connectionProvider.connectionName}-$name").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log("Uncaught exception in RabbitMQ consumer channel thread ${thread.name}")
                }
                channelThread.set(this)
            }
        }
        .asCoroutineDispatcher()

    private val processingScope = CoroutineScope(
        SupervisorJob() + processingDispatcher + CoroutineName("RabbitConsumer-${connectionProvider.connectionName}-$name") +
                CoroutineExceptionHandler { _, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log("Unhandled exception while processing a RabbitMQ delivery")
                }
    )

    private var channel: Channel? = null
    private val closed = AtomicBoolean()

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
        handler: suspend (consumerTag: String, message: Delivery, ack: RabbitAck) -> Unit
    ): String = withContext(channelDispatcher) {
        val channel = getChannel()

        if (!autoAck && prefetchCount > 0) {
            channel.basicQos(prefetchCount)
        }

        val callback = DeliverCallback { consumerTag, message ->
            val ack = RabbitAck(
                channelDispatcher = channelDispatcher,
                channel = channel,
                deliveryTag = message.envelope.deliveryTag,
                enabled = !autoAck
            )

            processingScope.launch {
                try {
                    handler(consumerTag, message, ack)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                    e.rethrowIfFatal()

                    try {
                        ack.nackIfUnsettled(requeue = requeueOnHandlerError)
                    } catch (acknowledgementError: Throwable) {
                        acknowledgementError.rethrowIfFatal()
                        e.addSuppressed(acknowledgementError)
                        log.atSevere()
                            .withCause(e)
                            .log("RabbitMQ delivery handler and negative acknowledgement both failed")
                        return@launch
                    }

                    log.atWarning()
                        .withCause(e)
                        .log(
                            "RabbitMQ delivery handler failed; message was negatively acknowledged " +
                                    "(requeue=$requeueOnHandlerError)"
                        )
                }
            }
        }

        channel.basicConsume(
            queue,
            autoAck,
            callback,
        ) { consumerTag ->
            if (!closed.get()) {
                log.atWarning().log("RabbitMQ consumer '$consumerTag' was cancelled by the broker")
            }
        }
    }

    suspend fun cancel(consumerTag: String) {
        withContext(channelDispatcher) {
            getChannel().basicCancel(consumerTag)
        }
    }

    private fun getChannel(): Channel {
        if (closed.get()) throw SurfRabbitConnectionClosedException("use consumer '$name'")

        val channel = this.channel
        if (channel != null && channel.isOpen) {
            return channel
        }

        val newChannel = connectionProvider.createChannel()
        this.channel = newChannel
        return newChannel
    }

    private fun resetChannel() {
        val currentChannel = channel
        channel = null
        currentChannel?.close()
    }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        processingScope.cancel()

        try {
            if (Thread.currentThread() === channelThread.get()) {
                resetChannel()
            } else {
                runBlocking(channelDispatcher) {
                    resetChannel()
                }
            }
        } finally {
            channelDispatcher.close()
        }
    }
}
