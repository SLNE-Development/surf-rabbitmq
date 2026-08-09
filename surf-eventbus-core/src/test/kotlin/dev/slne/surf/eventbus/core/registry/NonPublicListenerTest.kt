package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A handler on a non-public listener class must still be callable.
 *
 * `Class.methods` lists a public method even when its declaring class is package-private, so
 * registration succeeded and the handler looked live — but the reflective call from
 * `EventDispatcher`, in another package, threw `IllegalAccessException` on every delivery. A
 * throwing handler is contained by design, so the only trace was one `EVENT_HANDLER_FAILED`
 * row per event: the handler was registered, reported healthy, and silently never ran.
 *
 * Found by [dev.slne.surf.eventbus.suite.EventSuiteTest] against a real broker; pinned here so
 * it does not need a container to catch again.
 */
class NonPublicListenerTest {

    @Test
    fun `a handler on a non-public class is made accessible at registration`() {
        val registry = EventSubscriptionRegistry()
        registry.register(NonPublicListener())

        val subscription = registry.subscriptions().single()

        assertTrue(
            subscription.method.canAccess(subscription.listener),
            "the dispatcher lives in another package; without trySetAccessible the call throws"
        )
    }
}

@Serializable
@BusEvent("registry.nonpublic")
class NonPublicEvent(val value: String) : SurfBusEvent()

private class NonPublicListener {
    @SurfSubscribe("registry.nonpublic")
    @Suppress("unused")
    fun onEvent(event: NonPublicEvent) = Unit
}
