package dev.slne.surf.eventbus.rabbitmq.api.rpc.type

import dev.slne.surf.eventbus.InternalEventBusApi


@InternalEventBusApi
class RabbitRpcParameterDefault(
    override val name: String,
    override val type: RabbitRpcType,
    override val isOptional: Boolean,
    override val annotations: List<Annotation>
) : RabbitRpcParameter