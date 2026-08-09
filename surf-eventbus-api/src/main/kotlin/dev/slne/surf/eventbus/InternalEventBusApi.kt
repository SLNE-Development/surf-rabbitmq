package dev.slne.surf.eventbus

import dev.slne.surf.api.shared.api.annotation.InternalAPIMarker

/**
 * Marks a declaration as internal to surf-eventbus.
 *
 * One marker for the whole project. Three of them — one per merged sub-project — meant a
 * consumer had to opt in three times to reach one coherent internal surface, and the three
 * disagreed on level, targets and even on what the project is called.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to surf-eventbus. It can change in any release."
)
@InternalAPIMarker
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    // Deliberately not VALUE_PARAMETER: Kotlin rejects an opt-in marker on a parameter
    // ("Opt-in requirement marker annotation cannot be used on parameter"), and adding it here
    // silently retargets the existing constructor-property annotations from the property to the
    // parameter, which then fails to compile.
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalEventBusApi
