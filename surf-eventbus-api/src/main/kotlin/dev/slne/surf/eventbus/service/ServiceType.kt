package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

/**
 * A type as the generated descriptor sees it: the Kotlin type plus the annotations written on
 * it at the use site.
 *
 * The annotations are what make `@Contextual` and `@Serializable(with = ...)` reachable at
 * runtime. The query path used a bare `KType` and silently dropped them.
 */
@InternalEventBusApi
interface ServiceType {
    val kType: KType

    /** Annotations with target [AnnotationTarget.TYPE]. */
    val annotations: List<Annotation>
}
