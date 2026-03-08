package dev.slne.surf.rabbitmq.api.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.connection.AMQPConnection
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi

interface RabbitMQConnection {
    val connection: AMQPConnection
    val channel: AMQPChannel

    @InternalRabbitMQ
    suspend fun connect()

    @InternalRabbitMQ
    suspend fun disconnect()

    companion object {
        @InternalRabbitMQ
        fun create(api: RabbitMQApi): RabbitMQConnection = RabbitMQConnectionFactory.get().createConnection(api)
    }
}