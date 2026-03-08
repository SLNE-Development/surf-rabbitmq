package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.surfapi.core.api.util.requiredService

@InternalRabbitMQ
interface RabbitMQConnectionFactory {

    fun createConnection(api: RabbitMQApi): RabbitMQConnection

    companion object {
        val instance = requiredService<RabbitMQConnectionFactory>()
        fun get() = instance
    }
}