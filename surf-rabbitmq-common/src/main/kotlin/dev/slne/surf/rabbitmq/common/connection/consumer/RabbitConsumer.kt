package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.*
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import kotlinx.coroutines.*
import java.lang.AutoCloseable
import java.util.concurrent.Executors

class RabbitConsumer(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AutoCloseable {

    private val channelDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "rabbit-consumer-channel-${connectionProvider.connectionName}-$name").apply {
                isDaemon = true
            }
        }
        .asCoroutineDispatcher()

    private val processingScope = CoroutineScope(
        SupervisorJob() + processingDispatcher
    )

    private var channel: Channel? = null

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
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }

                    ack.nackIfUnsettled(requeue = requeueOnHandlerError)
                }
            }
        }

        channel.basicConsume(
            queue,
            autoAck,
            callback,
            { _ ->
                // Consumer was cancelled by broker or client.
            }
        )
    }

    suspend fun cancel(consumerTag: String) {
        withContext(channelDispatcher) {
            getChannel().basicCancel(consumerTag)
        }
    }

    private fun getChannel(): Channel {
        val channel = this.channel
        if (channel != null && channel.isOpen) {
            return channel
        }

        val newChannel = connectionProvider.createChannel()
        this.channel = newChannel
        return newChannel
    }

    private fun resetChannel() {
        runCatching {
            channel?.close()
        }

        channel = null
    }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        processingScope.cancel()

        try {
            runBlocking(channelDispatcher) {
                resetChannel()
            }
        } finally {
            channelDispatcher.close()
        }
    }
}