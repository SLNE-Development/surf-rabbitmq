package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor.RpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget

@InternalEventBusApi
class RabbitRpcCall(
    val descriptor: RpcServiceDescriptor<*>,
    val callableName: String,
    val arguments: Array<Any?>,
    val serviceId: Long,
    val target: RabbitTarget
)
