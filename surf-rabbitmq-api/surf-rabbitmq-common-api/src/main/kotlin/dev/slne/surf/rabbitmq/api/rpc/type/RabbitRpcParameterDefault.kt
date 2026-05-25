package dev.slne.surf.rabbitmq.api.rpc.type

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
class RabbitRpcParameterDefault(
    override val name: String,
    override val type: RabbitRpcType,
    override val isOptional: Boolean,
    override val annotations: List<Annotation>
) : RabbitRpcParameter