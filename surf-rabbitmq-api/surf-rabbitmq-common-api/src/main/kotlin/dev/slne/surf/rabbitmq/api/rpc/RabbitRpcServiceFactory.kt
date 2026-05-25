package dev.slne.surf.rabbitmq.api.rpc

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi

@InternalRabbitMQ
interface RabbitRpcServiceFactory {

    fun createRpcService(api: RabbitMQApi): RabbitRpcService

    @InternalRabbitMQ
    companion object {
        val instance = requiredService<RabbitRpcServiceFactory>()
    }
}