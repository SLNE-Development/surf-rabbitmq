package dev.slne.surf.eventbus.rabbitmq.api.rpc.type

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

@InternalEventBusApi
class RabbitRpcTypeDefault(
    override val kType: KType,
    override val annotations: List<Annotation>
) : RabbitRpcType {
    override fun toString(): String {
        return kType.toString()
    }
}