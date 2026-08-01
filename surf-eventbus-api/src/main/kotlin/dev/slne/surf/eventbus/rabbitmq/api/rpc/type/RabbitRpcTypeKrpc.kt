package dev.slne.surf.eventbus.rabbitmq.api.rpc.type

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

@InternalEventBusApi
class RabbitRpcTypeKrpc(
    override val kType: KType,
    override val annotations: List<Annotation>,

    /**
     * Contains serializer instances from [kotlinx.serialization.Serializable.with] parameters from [annotations],
     * mapped by their [KClass].
     */
    val serializers: Map<KClass<out KSerializer<*>>, KSerializer<*>>,
) : RabbitRpcType {
    override fun toString(): String {
        return kType.toString()
    }
}