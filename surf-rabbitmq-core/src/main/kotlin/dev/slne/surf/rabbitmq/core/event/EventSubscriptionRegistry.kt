package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Discovers `@RabbitSubscribe` methods and reports the bindings they need.
 *
 * Everything is validated at registration rather than on delivery. A malformed pattern binds
 * without complaint on the broker and then matches nothing, so the only visible symptom would
 * be an event that never arrives — which is nearly impossible to diagnose in production.
 */
class EventSubscriptionRegistry {

    private val entries = CopyOnWriteArrayList<EventSubscription>()

    /**
     * Registers every annotated method on [listener].
     *
     * @throws IllegalArgumentException if a method has the wrong shape or an invalid pattern
     */
    fun register(listener: Any) {
        for (method in listener.javaClass.declaredMethods) {
            val annotation = method.getAnnotation(RabbitSubscribe::class.java) ?: continue

            // A suspend function carries a hidden trailing Continuation parameter.
            val isSuspend = method.parameterTypes.lastOrNull()?.name ==
                    "kotlin.coroutines.Continuation"
            val declaredCount = if (isSuspend) method.parameterCount - 1 else method.parameterCount

            require(declaredCount == 1) {
                "@RabbitSubscribe method ${listener.javaClass.name}#${method.name} must take " +
                        "exactly one parameter, but takes $declaredCount"
            }

            val parameterType = method.parameterTypes[0]
            require(RabbitEventPacket::class.java.isAssignableFrom(parameterType)) {
                "@RabbitSubscribe method ${listener.javaClass.name}#${method.name} must take a " +
                        "RabbitEventPacket subtype, but takes ${parameterType.name}"
            }

            @Suppress("UNCHECKED_CAST")
            val eventClass = parameterType as Class<out RabbitEventPacket>

            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
            EventTopics.validateBindingPattern(pattern)

            method.isAccessible = true

            entries += EventSubscription(
                eventClass = eventClass,
                pattern = pattern,
                mode = annotation.mode,
                retry = annotation.retry,
                listener = listener,
                method = method
            )
        }
    }

    fun subscriptions(): List<EventSubscription> = entries.toList()

    /** The binding patterns needed for [mode]'s queue. */
    fun patternsFor(mode: SubscriptionMode): Set<String> =
        entries.filter { it.mode == mode }.map { it.pattern }.toSet()

    /**
     * Every subscription that should receive an event of [eventClass] published under [topic].
     *
     * More than one may match: a handler bound to `faction.disbanded` and another bound to
     * `faction.#` both receive the same event, and both must run.
     */
    fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription> =
        entries.filter {
            it.eventClass.isAssignableFrom(eventClass) && EventTopics.matches(it.pattern, topic)
        }

    fun isEmpty(): Boolean = entries.isEmpty()
}
