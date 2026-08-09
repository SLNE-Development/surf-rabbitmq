package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.EventTopics
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds every `@SurfSubscribe` method of the process.
 *
 * Everything is validated here rather than at delivery: a wrong parameter type or a malformed
 * pattern would otherwise show up as an event that simply never arrives.
 */
class EventSubscriptionRegistry {

    private val entries = CopyOnWriteArrayList<EventSubscription>()

    @Volatile
    private var frozen = false

    fun register(listener: Any) {
        check(!frozen) { "registration is closed: freeze() has already run" }

        var found = 0

        for (method in listener.javaClass.methods) {
            val annotation = method.getAnnotation(SurfSubscribe::class.java) ?: continue
            found++

            // A suspend handler used to pass validation here - the trailing Continuation was
            // stripped before counting parameters - and then never run: the dispatcher invokes
            // with one argument, a suspend method's JVM signature takes two, and the resulting
            // IllegalArgumentException was swallowed by the containment block as one
            // EVENT_HANDLER_FAILED row per event. Registered, frozen, connected, silent.
            // Rejecting here is the honest answer until the dispatcher can actually call one.
            val isSuspend = method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
            require(!isSuspend) {
                "${listener.javaClass.name}#${method.name} is a suspend function, which the " +
                        "event dispatcher cannot invoke. Make it a regular function; if it " +
                        "needs to suspend, launch into your own scope from inside it."
            }

            require(method.parameterCount == 1) {
                "${listener.javaClass.name}#${method.name} must take exactly one parameter, " +
                        "the event; found ${method.parameterCount}"
            }

            val parameterType = method.parameterTypes[0]
            require(SurfBusEvent::class.java.isAssignableFrom(parameterType)) {
                "${listener.javaClass.name}#${method.name} takes ${parameterType.name}, " +
                        "which does not extend SurfBusEvent"
            }

            @Suppress("UNCHECKED_CAST")
            val eventClass = parameterType as Class<out SurfBusEvent>
            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
            EventTopics.validateBindingPattern(pattern)

            // A public method on a non-public class is not reflectively callable from another
            // package. Without this the dispatcher throws IllegalAccessException on every
            // delivery, and because a throwing handler is contained by design, the only trace
            // is one EVENT_HANDLER_FAILED row per event - the handler looks registered and
            // silently never runs. Done here, once, rather than per dispatch.
            runCatching { method.trySetAccessible() }

            entries += EventSubscription(
                eventClass = eventClass,
                pattern = pattern,
                includeSelf = annotation.includeSelf,
                listener = listener,
                method = method
            )
        }

        require(found > 0) {
            "${listener.javaClass.name} has no @SurfSubscribe method. Registering it is a no-op " +
                    "and most likely a mistake."
        }
    }

    fun freeze() {
        frozen = true
    }

    fun subscriptions(): List<EventSubscription> = entries.toList()

    fun patterns(): Set<String> = entries.mapTo(mutableSetOf()) { it.pattern }

    fun exactTopics(): Set<String> = patterns().filterNotTo(mutableSetOf(), ::hasWildcard)

    fun wildcardPatterns(): Set<String> = patterns().filterTo(mutableSetOf(), ::hasWildcard)

    /**
     * Every subscription whose pattern matches [topic] and whose parameter type is assignable
     * from [eventClass].
     *
     * Type-hierarchy aware on purpose: a handler declared on a base type is what makes
     * `@SurfSubscribe(topic = "faction.#")` usable at all.
     */
    fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription> =
        entries.filter { subscription ->
            subscription.eventClass.isAssignableFrom(eventClass) &&
                    EventTopics.matches(subscription.pattern, topic)
        }

    fun isEmpty(): Boolean = entries.isEmpty()

    private fun hasWildcard(pattern: String): Boolean = '*' in pattern || '#' in pattern
}
