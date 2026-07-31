package dev.slne.surf.eventbus.rabbitmq.api.connection

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi

@InternalRabbitMQ
interface RabbitMQConnectionFactory {
    fun createConnection(api: SurfRabbitApi): RabbitMQConnection

    @InternalRabbitMQ
    companion object : RabbitMQConnectionFactory by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<RabbitMQConnectionFactory>()
