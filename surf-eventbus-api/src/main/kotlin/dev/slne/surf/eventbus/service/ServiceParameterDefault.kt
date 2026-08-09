package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

@InternalEventBusApi
class ServiceParameterDefault(
    override val name: String,
    override val type: ServiceType,
    override val isOptional: Boolean,
    override val annotations: List<Annotation>,
) : ServiceParameter
