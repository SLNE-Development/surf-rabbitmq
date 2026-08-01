package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget

@InternalRabbitMQ
class RabbitRpcCall(
    val descriptor: RabbitRpcServiceDescriptor<*>,
    val callableName: String,
    val arguments: Array<Any?>,
    val serviceId: Long,
    val target: RabbitTarget
)
