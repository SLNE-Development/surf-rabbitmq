package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi

@InternalRabbitMQ
interface RabbitMQConnectionFactory {
    fun createConnection(api: RabbitMQApi): RabbitMQConnection

    @InternalRabbitMQ
    companion object : RabbitMQConnectionFactory by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<RabbitMQConnectionFactory>()
