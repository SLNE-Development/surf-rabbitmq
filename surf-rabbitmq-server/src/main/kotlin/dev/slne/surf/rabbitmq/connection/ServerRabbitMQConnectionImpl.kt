package dev.slne.surf.rabbitmq.connection

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

    override suspend fun connect() {
        super.connect()

        channel.basicQos(count = 1u)
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
                val correlationId = property.correlationId ?: continue
                val replyTo = property.replyTo ?: continue
                val body = message.message.body
                val deliveryTag = message.message.deliveryTag

                listenerHandler.handleRequest(correlationId, replyTo, body, deliveryTag)
            }
        }
    }

    fun replyToRequest(correlationId: String, replyTo: String, deliveryTag: ULong, body: ByteArray) {
        api.scope.launch {
            channel.basicPublish {
                this.body = body
                exchange = ""
                routingKey = replyTo
                properties = properties {
                    this.correlationId = correlationId
                    deliveryMode = 2u // Persistent message by default; only works with durable queues
                }
            }

            channel.basicAck(deliveryTag)
        }
    }
}