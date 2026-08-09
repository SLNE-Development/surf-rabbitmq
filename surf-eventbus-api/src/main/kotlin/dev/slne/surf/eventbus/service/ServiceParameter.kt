package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/** One parameter of a contract method, as seen by the generated descriptor. */
@InternalEventBusApi
interface ServiceParameter {
    val name: String
    val type: ServiceType

    /** Whether the method declares a default value, so the wire may omit it. */
    val isOptional: Boolean

    /** Annotations with target [AnnotationTarget.VALUE_PARAMETER]. */
    val annotations: List<Annotation>
}
