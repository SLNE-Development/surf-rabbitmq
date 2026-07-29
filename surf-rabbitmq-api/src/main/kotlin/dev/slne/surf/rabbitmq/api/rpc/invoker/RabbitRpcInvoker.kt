package dev.slne.surf.rabbitmq.api.rpc.invoker

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
fun interface RabbitRpcInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
