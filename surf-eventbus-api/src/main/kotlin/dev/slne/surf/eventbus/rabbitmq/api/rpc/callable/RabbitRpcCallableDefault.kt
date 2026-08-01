package dev.slne.surf.eventbus.rabbitmq.api.rpc.callable

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.rpc.invoker.RabbitRpcInvoker
import dev.slne.surf.eventbus.rabbitmq.api.rpc.type.RabbitRpcParameter
import dev.slne.surf.eventbus.rabbitmq.api.rpc.type.RabbitRpcType

@InternalRabbitMQ
class RabbitRpcCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: RabbitRpcType,
    override val invoker: RabbitRpcInvoker<Service>,
    override val parameters: Array<out RabbitRpcParameter>,
    override val fireAndForget: Boolean = false
) : RabbitRpcCallable<Service>