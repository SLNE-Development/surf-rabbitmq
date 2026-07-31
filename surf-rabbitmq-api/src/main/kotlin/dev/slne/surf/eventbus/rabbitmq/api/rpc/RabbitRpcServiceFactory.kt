package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi

@InternalRabbitMQ
interface RabbitRpcServiceFactory {

    fun createRpcService(api: SurfRabbitApi): RabbitRpcService

    @InternalRabbitMQ
    companion object {
        val instance = requiredService<RabbitRpcServiceFactory>()
    }
}
