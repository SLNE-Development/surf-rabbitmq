package dev.slne.surf.eventbus.rabbitmq.core.event

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEventPacket
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Invokes the handler methods matching a delivered event.
 *
 * Every matching subscription runs, including overlapping patterns: a handler bound to
 * `faction.disbanded` and one bound to `faction.#` both fire for the same event.
 *
 * If any handler throws, the exception propagates so the consumer can nack the delivery and
 * let the retry machinery in Plan 4 take over.
 */
class EventDispatcher(
    private val registry: EventSubscriptionRegistry
) {
    companion object {
        private val log = logger()
    }

    /**
     * Runs every subscription matching [event] and [topic].
     *
     * @throws Throwable the first handler failure, after all handlers have been attempted
     */
    suspend fun dispatch(event: RabbitEventPacket, topic: String) {
        val matching = registry.subscriptionsFor(event.javaClass, topic)

        if (matching.isEmpty()) {
            // Loud on purpose. Durable SHARED queues keep their bindings across deploys,
            // and bindings are only ever added - a pattern removed from the code keeps
            // routing events here, where they are acked and dropped. This log line is the
            // only trace of that drift; delete stale bindings via the management UI.
            log.atWarning().log(
                "No subscription matched event %s on topic %s - if this pattern was removed " +
                        "from the code, its binding on the shared event queue is stale",
                event.javaClass.name, topic
            )
            return
        }

        var firstFailure: Throwable? = null

        for (subscription in matching) {
            try {
                invoke(subscription, event)
            } catch (cause: Throwable) {
                // Keep going: one broken handler must not stop its unrelated neighbours.
                log.atSevere()
                    .withCause(cause)
                    .log(
                        "Handler %s#%s failed for event %s",
                        subscription.listener.javaClass.name,
                        subscription.method.name,
                        event.javaClass.name
                    )

                if (firstFailure == null) firstFailure = cause
            }
        }

        firstFailure?.let { throw it }
    }

    private suspend fun invoke(subscription: EventSubscription, event: RabbitEventPacket) {
        val method = subscription.method
        val isSuspend = method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"

        try {
            if (isSuspend) {
                suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    method.invoke(subscription.listener, event, continuation)
                }
            } else {
                method.invoke(subscription.listener, event)
            }
        } catch (wrapped: InvocationTargetException) {
            // Reflection wraps synchronous handler failures; retry decisions and logs must
            // see the handler's own exception, not the reflective envelope.
            throw wrapped.targetException
        }
    }
}
