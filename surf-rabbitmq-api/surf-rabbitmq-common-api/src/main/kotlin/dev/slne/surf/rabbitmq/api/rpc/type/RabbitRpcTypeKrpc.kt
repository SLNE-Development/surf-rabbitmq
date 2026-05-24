package dev.slne.surf.rabbitmq.api.rpc.type

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

@InternalRabbitMQ
class RabbitRpcTypeKrpc(
    override val kType: KType,
    override val annotations: List<Annotation>,

    /**
     * Contains serializer instances from [kotlinx.serialization.Serializable.with] parameters from [annotations],
     * mapped by their [KClass].
     */
    val serializers: Map<KClass<KSerializer<Any?>>, KSerializer<Any?>>,
) : RabbitRpcType {
    override fun toString(): String {
        return kType.toString()
    }
}