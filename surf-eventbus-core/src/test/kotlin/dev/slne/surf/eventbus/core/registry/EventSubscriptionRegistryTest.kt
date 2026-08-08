package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventSubscriptionRegistryTest {

    @Test
    fun `a handler without an explicit topic subscribes to the topic of its parameter`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)

        assertEquals(setOf("test.plain"), registry.patterns())
        assertEquals(1, registry.subscriptions().size)
        assertEquals(false, registry.subscriptions().single().includeSelf)
    }

    @Test
    fun `an explicit pattern wins over the topic of the parameter`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PatternListener)

        assertEquals(setOf("test.#"), registry.patterns())
    }

    @Test
    fun `exact and wildcard patterns are reported separately`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)
        registry.register(PatternListener)

        assertEquals(setOf("test.plain"), registry.exactTopics())
        assertEquals(setOf("test.#"), registry.wildcardPatterns())
    }

    @Test
    fun `a handler with two parameters is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(TwoParameterListener)
        }

        assertContains(failure.message!!, "exactly one parameter")
    }

    @Test
    fun `a handler whose parameter is not a bus event is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(WrongParameterListener)
        }

        assertContains(failure.message!!, "SurfBusEvent")
    }

    @Test
    fun `a malformed pattern is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(MalformedPatternListener)
        }

        assertContains(failure.message!!, "test..broken")
    }

    @Test
    fun `an event type without an annotation is rejected at registration`() {
        assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(UnannotatedEventListener)
        }
    }

    @Test
    fun `subscriptionsFor returns every matching subscription`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)
        registry.register(PatternListener)

        val matches = registry.subscriptionsFor(PlainEvent::class.java, "test.plain")

        assertEquals(2, matches.size, "both the exact and the wildcard handler must fire")
    }

    @Test
    fun `a handler on a base type receives a subtype`() {
        val registry = EventSubscriptionRegistry()
        registry.register(BaseTypeListener)

        val matches = registry.subscriptionsFor(SubEvent::class.java, "test.sub")

        assertEquals(1, matches.size)
    }

    @Test
    fun `registration after freeze is rejected`() {
        val registry = EventSubscriptionRegistry()
        registry.freeze()

        assertFailsWith<IllegalStateException> { registry.register(PlainListener) }
    }

    @Test
    fun `a listener without any annotated method is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(EmptyListener)
        }

        assertContains(failure.message!!, "@SurfSubscribe")
    }

    /**
     * Regression test for C2.
     *
     * A suspend handler used to register successfully: the registry stripped the trailing
     * `Continuation` before counting parameters, so validation passed, but the dispatcher
     * invokes the method with exactly one argument and a suspend method's JVM signature takes
     * two. Every delivery threw `wrong number of arguments`, the containment block swallowed
     * it, and the handler silently never ran.
     */
    @Test
    fun `a suspend handler is rejected at registration, naming the method`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(SuspendListener)
        }

        assertContains(failure.message!!, "onPlain")
        assertContains(failure.message!!, "suspend")
    }
}

@BusEvent("test.plain")
private class PlainEvent : SurfBusEvent()

@BusEvent("test.base")
private open class BaseEvent : SurfBusEvent()

@BusEvent("test.sub")
private class SubEvent : BaseEvent()

private class UnannotatedEvent : SurfBusEvent()

private object PlainListener {
    @SurfSubscribe
    fun onPlain(event: PlainEvent) = Unit
}

private object PatternListener {
    @SurfSubscribe(topic = "test.#")
    fun onAny(event: PlainEvent) = Unit
}

private object BaseTypeListener {
    @SurfSubscribe(topic = "test.#")
    fun onBase(event: BaseEvent) = Unit
}

private object TwoParameterListener {
    @SurfSubscribe
    fun onPlain(event: PlainEvent, extra: String) = Unit
}

private object WrongParameterListener {
    @SurfSubscribe
    fun onString(event: String) = Unit
}

private object MalformedPatternListener {
    @SurfSubscribe(topic = "test..broken")
    fun onPlain(event: PlainEvent) = Unit
}

private object UnannotatedEventListener {
    @SurfSubscribe
    fun onUnannotated(event: UnannotatedEvent) = Unit
}

private object EmptyListener

private object SuspendListener {
    @Suppress("RedundantSuspendModifier")
    @SurfSubscribe
    suspend fun onPlain(event: PlainEvent) = Unit
}
