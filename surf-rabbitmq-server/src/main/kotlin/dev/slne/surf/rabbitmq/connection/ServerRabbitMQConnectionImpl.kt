package dev.slne.surf.rabbitmq.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.listener.RabbitListenerHandlerManager
import kotlinx.coroutines.launch

class ServerRabbitMQConnectionImpl(private val api: RabbitMQApi, config: RabbitMQConfig) :
    AbstractRabbitMQConnectionImpl(api, config), ServerRabbitMQConnection {

    private val listenerHandler = RabbitListenerHandlerManager(api, this)
    private val prefetchCount = config.serverPrefetchCount.coerceAtLeast(1).toUShort()
    private val persistResponses = config.persistResponses

    private lateinit var replyChannel: AMQPChannel

    override suspend fun connect() {
        super.connect()
        replyChannel = connection.openChannel()

        channel.basicQos(count = prefetchCount)
        startConsumingRequests()
    }

    override fun registerRequestHandler(instance: Any) {
        listenerHandler.registerRequestHandler(instance)
    }

    private suspend fun startConsumingRequests() {
        val consume = channel.basicConsume(queueName)

        api.scope.launch {
            for (message in consume) {
                val property = message.message.properties
                val body = message.message.body
                val deliveryTag = message.message.deliveryTag
                val correlationId = property.correlationId
                val replyTo = property.replyTo

                if (correlationId == null || replyTo == null) {
                    nackRequest(deliveryTag)
                    continue
                }

                api.scope.launch {
                    listenerHandler.handleRequest(correlationId, replyTo, body, deliveryTag)
                }
            }
        }
    }

    suspend fun replyToRequest(correlationId: String, replyTo: String, deliveryTag: ULong, body: ByteArray) {
        replyChannel.basicPublish {
            this.body = body
            exchange = ""
            routingKey = replyTo
            properties = properties {
                this.correlationId = correlationId
                deliveryMode = if (persistResponses) 2u else 1u
            }
        }

        channel.basicAck(deliveryTag)
    }

    suspend fun nackRequest(deliveryTag: ULong) {
        channel.basicNack(deliveryTag, requeue = false)
    }

    override suspend fun disconnect() {
        if (this::replyChannel.isInitialized) {
            replyChannel.close()
        }
        super.disconnect()
    }
}