package dev.slne.surf.eventbus.rabbitmq.api.rpc.callable

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.rpc.invoker.RabbitRpcInvoker
import dev.slne.surf.eventbus.rabbitmq.api.rpc.type.RabbitRpcParameter
import dev.slne.surf.eventbus.rabbitmq.api.rpc.type.RabbitRpcType

@InternalEventBusApi
interface RabbitRpcCallable<Service: Any> {
    val name: String
    val returnType: RabbitRpcType
    val invoker: RabbitRpcInvoker<Service>
    val parameters: Array<out RabbitRpcParameter>

    /** Whether this callable is `@FireAndForget`: no reply is awaited or sent. */
    val fireAndForget: Boolean
}