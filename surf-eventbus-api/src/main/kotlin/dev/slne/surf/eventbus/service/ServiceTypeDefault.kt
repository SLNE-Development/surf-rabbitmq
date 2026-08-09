package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

@InternalEventBusApi
class ServiceTypeDefault(
    override val kType: KType,
    override val annotations: List<Annotation>,
) : ServiceType {
    override fun toString(): String = kType.toString()
}
