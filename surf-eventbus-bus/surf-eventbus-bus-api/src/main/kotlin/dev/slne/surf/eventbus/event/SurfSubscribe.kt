package dev.slne.surf.eventbus.event

/**
 * Marks a method as an event handler.
 *
 * @property topic pattern to subscribe to. Empty means the topic of the parameter type.
 *   `*` matches exactly one segment, `#` zero or more.
 * @property includeSelf whether this handler also sees events published by its own process.
 *   `false` by default because all ~20 pre-2.0 handlers opened with an
 *   `originatesFromThisClient()` check and forgetting it is the usual cause of a feedback loop.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SurfSubscribe(
    val topic: String = "",
    val includeSelf: Boolean = false
)
