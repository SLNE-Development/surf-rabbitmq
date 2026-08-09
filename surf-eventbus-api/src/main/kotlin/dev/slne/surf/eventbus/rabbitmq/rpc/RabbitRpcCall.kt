package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.rpc.descriptor.RpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget

@InternalEventBusApi
class RabbitRpcCall(
    val descriptor: RpcServiceDescriptor<*>,
    val callableName: String,
    val arguments: Array<Any?>,
    val serviceId: Long,
    val target: RabbitTarget
)
