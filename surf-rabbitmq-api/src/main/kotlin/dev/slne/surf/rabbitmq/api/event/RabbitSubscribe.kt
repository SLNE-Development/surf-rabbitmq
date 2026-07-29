package dev.slne.surf.rabbitmq.api.event

/**
 * Marks a method as an event handler.
 *
 * The method must take exactly one parameter, a subtype of [RabbitEventPacket], and may be a
 * `suspend` function.
 *
 * ```kotlin
 * object CacheListener {
 *     // one instance handles it - the default
 *     @RabbitSubscribe
 *     suspend fun onDisbanded(event: FactionDisbandedEvent) { … }
 *
 *     // every instance handles it
 *     @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
 *     suspend fun onReload(event: ConfigReloadedEvent) { … }
 *
 *     // wider pattern than the event's own topic
 *     @RabbitSubscribe(topic = "faction.#")
 *     suspend fun onAnyFactionEvent(event: FactionEvent) { … }
 * }
 * ```
 *
 * @property topic binding pattern; defaults to the event type's own [RabbitEvent.topic].
 *   May contain `*` (exactly one segment) and `#` (zero or more segments).
 * @property mode whether one instance or every instance handles the event
 * @property retry whether a failed handler is retried. Set `false` for handlers that are not
 *   idempotent — a retried handler may run twice for the same event.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitSubscribe(
    val topic: String = "",
    val mode: SubscriptionMode = SubscriptionMode.SHARED,
    val retry: Boolean = true
)
