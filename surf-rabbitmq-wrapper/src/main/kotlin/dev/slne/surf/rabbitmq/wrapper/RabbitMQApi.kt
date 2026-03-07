package dev.slne.surf.rabbitmq.wrapper

import dev.kourier.amqp.BuiltinExchangeType
import dev.kourier.amqp.Properties
import dev.kourier.amqp.Table
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.connection.AMQPConfig
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.wrapper.config.RabbitMQConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

interface RabbitMQApi {
    val scope: CoroutineScope
    val config: RabbitMQConfig
    val rabbitConfig: AMQPConfig

    val connection: AMQPConnection
    val channel: AMQPChannel

    suspend fun connect()
    suspend fun disconnect()

    suspend fun declareExchange(
        exchangeName: String,
        type: String = BuiltinExchangeType.FANOUT,
        durable: Boolean = true,
        autoDelete: Boolean = false,
        internal: Boolean = false,
        arguments: Table = emptyMap(),
    )

    suspend fun declareQueue(
        queueName: String,
        durable: Boolean = true,
        exclusive: Boolean = false,
        autoDelete: Boolean = false,
    )

    suspend fun declareCallbackQueue(
        queueName: String,
        callbackQueueName: String = "${queueName}_callback",
        durable: Boolean = true,
    )

    suspend fun <Message : Any> publish(
        messageClass: KClass<Message>,
        message: Message,
        queueName: String,
        exchange: String = "",
        mandatory: Boolean = false,
        immediate: Boolean = false,
        properties: Properties = properties {
            deliveryMode = 2u // Persistent message by default, only works with durable queues
        }
    )
}

suspend inline fun <reified Message : Any> RabbitMQApi.publish(
    message: Message,
    queueName: String,
    exchange: String = "",
    mandatory: Boolean = false,
    immediate: Boolean = false,
    properties: Properties = properties {
        deliveryMode = 2u // Persistent message by default, only works with durable queues
    }
) {
    publish(Message::class, message, queueName, exchange, mandatory, immediate, properties)
}