package dev.slne.surf.eventbus.rabbitmq.api.connection

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi

@InternalEventBusApi
interface RabbitMQConnectionFactory {
    fun createConnection(api: SurfRabbitApi): RabbitMQConnection

    @InternalEventBusApi
    companion object : RabbitMQConnectionFactory by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<RabbitMQConnectionFactory>()
