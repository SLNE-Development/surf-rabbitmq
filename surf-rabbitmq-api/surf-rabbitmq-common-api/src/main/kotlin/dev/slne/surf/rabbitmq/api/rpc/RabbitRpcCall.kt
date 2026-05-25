package dev.slne.surf.rabbitmq.api.rpc

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor

@InternalRabbitMQ
class RabbitRpcCall(
    val descriptor: RabbitRpcServiceDescriptor<*>,
    val callableName: String,
    val arguments: Array<Any?>,
    val serviceId: Long
)