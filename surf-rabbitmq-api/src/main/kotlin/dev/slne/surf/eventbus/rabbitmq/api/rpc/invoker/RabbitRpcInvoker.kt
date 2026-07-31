package dev.slne.surf.eventbus.rabbitmq.api.rpc.invoker

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
fun interface RabbitRpcInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
