package dev.slne.surf.rabbitmq.api.rpc.callable

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.rpc.invoker.RabbitRpcInvoker
import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcParameter
import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcType

@InternalRabbitMQ
class RabbitRpcCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: RabbitRpcType,
    override val invoker: RabbitRpcInvoker<Service>,
    override val parameters: Array<out RabbitRpcParameter>
) : RabbitRpcCallable<Service>