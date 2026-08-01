package dev.slne.surf.eventbus

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to surf-eventbus. It can change in any release."
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_SETTER)
annotation class InternalEventBusApi
