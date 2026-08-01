package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi

@InternalEventBusApi
interface RabbitRpcServiceFactory {

    fun createRpcService(api: SurfRabbitApi): RabbitRpcService

    @InternalEventBusApi
    companion object {
        val instance = requiredService<RabbitRpcServiceFactory>()
    }
}
