package dev.slne.surf.rabbitmq.api.rpc.type

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import kotlin.reflect.KType

@InternalRabbitMQ
class RabbitRpcTypeDefault(
    override val kType: KType,
    override val annotations: List<Annotation>
) : RabbitRpcType {
    override fun toString(): String {
        return kType.toString()
    }
}