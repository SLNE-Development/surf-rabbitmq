package dev.slne.surf.rabbitmq.api.rpc.callable

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.rpc.invoker.RabbitRpcInvoker
import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcParameter
import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcType

@InternalRabbitMQ
interface RabbitRpcCallable<Service: Any> {
    val name: String
    val returnType: RabbitRpcType
    val invoker: RabbitRpcInvoker<Service>
    val parameters: Array<out RabbitRpcParameter>
}