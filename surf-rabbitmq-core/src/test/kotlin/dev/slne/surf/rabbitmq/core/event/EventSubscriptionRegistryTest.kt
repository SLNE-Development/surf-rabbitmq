package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
@RabbitEvent("faction.disbanded")
class DisbandedEvent(val id: String) : RabbitEventPacket()

@Serializable
@RabbitEvent("config.reloaded")
class ReloadedEvent : RabbitEventPacket()

class EventSubscriptionRegistryTest {

    object Listener {
        @RabbitSubscribe
        suspend fun onDisbanded(event: DisbandedEvent) = Unit

        @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
        suspend fun onReloaded(event: ReloadedEvent) = Unit

        @RabbitSubscribe(topic = "faction.#", retry = false)
        suspend fun onAnyFaction(event: DisbandedEvent) = Unit
    }

    object BadArity {
        @RabbitSubscribe
        fun tooMany(a: DisbandedEvent, b: String) = Unit
    }

    object BadType {
        @RabbitSubscribe
        fun notAnEvent(a: String) = Unit
    }

    object BadPattern {
        @RabbitSubscribe(topic = "faction..x")
        fun bad(a: DisbandedEvent) = Unit
    }

    @Test
    fun `subscriptions are discovered`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertEquals(3, registry.subscriptions().size)
    }

    @Test
    fun `the pattern defaults to the event's own topic`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onDisbanded" }
        assertEquals("faction.disbanded", sub.pattern)
    }

    @Test
    fun `an explicit topic overrides the event's own`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onAnyFaction" }
        assertEquals("faction.#", sub.pattern)
    }

    @Test
    fun `the default mode is SHARED`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onDisbanded" }
        assertEquals(
            SubscriptionMode.SHARED, sub.mode,
            "SHARED must be the default: defaulting to BROADCAST would silently run every " +
                    "side-effecting handler once per instance"
        )
    }

    @Test
    fun `an explicit mode is honoured`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onReloaded" }
        assertEquals(SubscriptionMode.BROADCAST, sub.mode)
    }

    @Test
    fun `retry defaults to true and can be disabled`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertTrue(registry.subscriptions().first { it.method.name == "onDisbanded" }.retry)
        assertTrue(!registry.subscriptions().first { it.method.name == "onAnyFaction" }.retry)
    }

    @Test
    fun `patterns are grouped by mode`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertEquals(
            setOf("faction.disbanded", "faction.#"),
            registry.patternsFor(SubscriptionMode.SHARED)
        )
        assertEquals(setOf("config.reloaded"), registry.patternsFor(SubscriptionMode.BROADCAST))
    }

    @Test
    fun `a wrong parameter count is rejected at registration`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadArity)
        }
        assertTrue(thrown.message!!.contains("exactly one parameter"))
    }

    @Test
    fun `a non-event parameter is rejected at registration`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadType)
        }
        assertTrue(thrown.message!!.contains("RabbitEventPacket"))
    }

    @Test
    fun `a malformed pattern is rejected at registration`() {
        // Better a startup failure than a binding that silently never matches.
        assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadPattern)
        }
    }

    @Test
    fun `an event is routed to every matching subscription`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val matching = registry.subscriptionsFor(DisbandedEvent::class.java, "faction.disbanded")

        assertEquals(
            2, matching.size,
            "both the exact subscription and the faction.# subscription must match"
        )
    }

    @Test
    fun `a fresh registry is empty`() {
        assertTrue(EventSubscriptionRegistry().isEmpty())
    }
}
