package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Spec tests 1–12c: events over a real Redis, through the whole bus.
 *
 * [dev.slne.surf.eventbus.redis.bus.RedisEventTransportTest] already drives the transport
 * directly. What only this level can show is that the registry's wildcard matching and the
 * transport's agree, and that a handler that throws is contained by the dispatcher rather than
 * by the transport.
 */
@RequiresDocker
class EventSuiteTest : RedisBusSuite() {

    private suspend fun settle() = delay(SETTLE_MILLIS)

    @Test
    fun `1 - a broadcast reaches all three instances`() = runBlocking {
        val received = AtomicInteger()
        repeat(3) { index ->
            connectedBus("suite-broadcast-$index") { it.subscribe(CountingListener(received)) }
        }
        val publisher = connectedBus("suite-broadcast-publisher")

        publisher.publish(SuiteEvent("hello"))
        settle()

        assertEquals(3, received.get(), "every subscribed instance must process the event")
    }

    @Test
    fun `2 - a wildcard-free topic reaches only the matching subscriber`() = runBlocking {
        val matching = AtomicInteger()
        val other = AtomicInteger()

        connectedBus("suite-exact") {
            it.subscribe(CountingListener(matching))
            it.subscribe(OtherTopicListener(other))
        }
        // A separate publisher: @SurfSubscribe defaults to includeSelf = false, so a bus does
        // not hear itself. That is the documented default, not a limitation of the suite.
        val publisher = connectedBus("suite-exact-publisher")

        publisher.publish(SuiteEvent("x"))
        settle()

        assertEquals(1, matching.get())
        assertEquals(0, other.get(), "a different topic must not be delivered")
    }

    @Test
    fun `3 - a star matches exactly one segment`() = runBlocking {
        val hits = ConcurrentLinkedQueue<String>()
        connectedBus("suite-star") { it.subscribe(StarListener(hits)) }
        val publisher = connectedBus("suite-star-publisher")

        publisher.publish(OneSegmentEvent("a"))
        publisher.publish(TwoSegmentEvent("b"))
        settle()

        assertEquals(
            listOf("a"),
            hits.toList(),
            "'suite.star.*' matches one segment, so the two-segment topic must not arrive"
        )
    }

    @Test
    fun `4 - a hash matches zero or more segments`() = runBlocking {
        val hits = ConcurrentLinkedQueue<String>()
        connectedBus("suite-hash") { it.subscribe(HashListener(hits)) }
        val publisher = connectedBus("suite-hash-publisher")

        publisher.publish(OneSegmentEvent("a"))
        publisher.publish(TwoSegmentEvent("b"))
        settle()

        assertEquals(setOf("a", "b"), hits.toSet(), "'#' matches both depths")
    }

    @Test
    fun `5 - two overlapping patterns both fire`() = runBlocking {
        val star = ConcurrentLinkedQueue<String>()
        val hash = ConcurrentLinkedQueue<String>()

        connectedBus("suite-overlap") {
            it.subscribe(StarListener(star))
            it.subscribe(HashListener(hash))
        }
        val publisher = connectedBus("suite-overlap-publisher")

        publisher.publish(OneSegmentEvent("a"))
        settle()

        assertEquals(listOf("a"), star.toList())
        assertEquals(listOf("a"), hash.toList())
    }

    @Test
    fun `9 - a failing handler does not block its neighbours`() = runBlocking {
        val survivor = AtomicInteger()

        connectedBus("suite-failing") {
            it.subscribe(ThrowingListener())
            it.subscribe(CountingListener(survivor))
        }
        val publisher = connectedBus("suite-failing-publisher")

        publisher.publish(SuiteEvent("x"))
        settle()

        assertEquals(1, survivor.get(), "one handler throwing must not swallow the other")
    }

    @Test
    fun `10 - an event with no subscriber is not an error`() = runBlocking {
        val bus = connectedBus("suite-nosubs")

        bus.publish(SuiteEvent("nobody is listening"))
        settle()
    }

    @Test
    fun `11 - an event with every subscriber offline expires`() = runBlocking {
        // The documented property, not a bug: events have no durability. A subscriber that
        // connects afterwards must not receive what it missed.
        val publisher = connectedBus("suite-expire-publisher")
        publisher.publish(SuiteEvent("into the void"))
        settle()

        val late = AtomicInteger()
        connectedBus("suite-expire-subscriber") { it.subscribe(CountingListener(late)) }
        settle()

        assertEquals(0, late.get(), "a late subscriber must not receive a past event")
    }

    @Test
    fun `12a - an event with a codec rides the binary channel and round-trips`() = runBlocking {
        val received = ConcurrentLinkedQueue<String>()

        connectedBus("suite-codec-subscriber") { it.subscribe(CodecListener(received)) }
        val publisher = connectedBus("suite-codec-publisher")

        publisher.publish(CodecEvent("binary"))
        settle()

        assertEquals(listOf("binary"), received.toList())
    }
}

@Serializable
@BusEvent("suite.event")
class SuiteEvent(val value: String) : SurfBusEvent()

@Serializable
@BusEvent("suite.other")
class SuiteOtherEvent(val value: String) : SurfBusEvent()

@Serializable
@BusEvent("suite.star.one")
class OneSegmentEvent(val value: String) : SurfBusEvent()

@Serializable
@BusEvent("suite.star.one.two")
class TwoSegmentEvent(val value: String) : SurfBusEvent()

@Serializable
@BusEvent("suite.codec")
class CodecEvent(val value: String) : SurfBusEvent()

private class CountingListener(private val counter: AtomicInteger) {
    @SurfSubscribe("suite.event")
    @Suppress("unused")
    fun onEvent(event: SuiteEvent) {
        counter.incrementAndGet()
    }
}

private class OtherTopicListener(private val counter: AtomicInteger) {
    @SurfSubscribe("suite.other")
    @Suppress("unused")
    fun onEvent(event: SuiteOtherEvent) {
        counter.incrementAndGet()
    }
}

private class StarListener(private val hits: ConcurrentLinkedQueue<String>) {
    @SurfSubscribe("suite.star.*")
    @Suppress("unused")
    fun onOne(event: OneSegmentEvent) {
        hits += event.value
    }
}

private class HashListener(private val hits: ConcurrentLinkedQueue<String>) {
    @SurfSubscribe("suite.star.#")
    @Suppress("unused")
    fun onOne(event: OneSegmentEvent) {
        hits += event.value
    }

    @SurfSubscribe("suite.star.#")
    @Suppress("unused")
    fun onTwo(event: TwoSegmentEvent) {
        hits += event.value
    }
}

private class ThrowingListener {
    @SurfSubscribe("suite.event")
    @Suppress("unused")
    fun onEvent(event: SuiteEvent): Unit = error("this handler always fails")
}

private class CodecListener(private val received: ConcurrentLinkedQueue<String>) {
    @SurfSubscribe("suite.codec")
    @Suppress("unused")
    fun onEvent(event: CodecEvent) {
        received += event.value
    }
}
