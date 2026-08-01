package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget

@InternalEventBusApi
class RabbitRpcCall(
    val descriptor: RabbitRpcServiceDescriptor<*>,
    val callableName: String,
    val arguments: Array<Any?>,
    val serviceId: Long,
    val target: RabbitTarget
)
