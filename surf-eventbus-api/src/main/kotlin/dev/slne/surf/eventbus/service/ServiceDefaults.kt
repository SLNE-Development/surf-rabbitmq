package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

@InternalEventBusApi
class ServiceTypeDefault(
    override val kType: KType,
    override val annotations: List<Annotation>
) : ServiceType {
    override fun toString(): String = kType.toString()
}

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

@InternalEventBusApi
class ServiceParameterDefault(
    override val name: String,
    override val type: ServiceType,
    override val isOptional: Boolean,
    override val annotations: List<Annotation>
) : ServiceParameter

@InternalEventBusApi
class ServiceCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: ServiceType,
    override val invoker: ServiceInvoker<Service>,
    override val parameters: Array<out ServiceParameter>,
    override val fireAndForget: Boolean = false
) : ServiceCallable<Service>
