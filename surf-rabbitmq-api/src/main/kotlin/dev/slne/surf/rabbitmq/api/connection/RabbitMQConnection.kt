package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi

@InternalRabbitMQ
interface RabbitMQConnection {
    suspend fun connect()
    suspend fun disconnect()


    @InternalRabbitMQ
    companion object {
        fun create(api: RabbitMQApi): RabbitMQConnection = RabbitMQConnectionFactory.createConnection(api)
    }
}