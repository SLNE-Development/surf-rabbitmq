package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * A [ServiceType] that also carries the serializer instances named by
 * `@Serializable(with = ...)` on this type, keyed by their class.
 */
@InternalEventBusApi
class ServiceTypeKrpc(
    override val kType: KType,
    override val annotations: List<Annotation>,
    val serializers: Map<KClass<out KSerializer<*>>, KSerializer<*>>,
) : ServiceType {
    override fun toString(): String = kType.toString()
}
