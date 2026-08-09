package dev.slne.surf.eventbus.event

/**
 * The topic under which instances of the annotated event type travel.
 *
 * Dot-separated and wildcard-free. Declaring it at the type rather than at the call site means a
 * publisher cannot send the same event under two keys.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BusEvent(val topic: String)
