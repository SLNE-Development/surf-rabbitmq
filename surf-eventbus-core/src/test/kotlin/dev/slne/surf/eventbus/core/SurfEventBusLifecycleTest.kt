package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.query.QueryService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SurfEventBusLifecycleTest {

    private val dataPath = Files.createTempDirectory("bus-lifecycle")

    private fun builder() = SurfEventBus.builder("surf-test", dataPath).instanceName("lobby-1")

    @Test
    fun `a builder without any transport fails`() {
        val failure = assertFailsWith<IllegalStateException> { builder().build() }

        assertContains(failure.message!!, "withRabbit")
        assertContains(failure.message!!, "withRedis")
    }

    @Test
    fun `publish without the redis transport names the missing builder call`() = runBlocking {
        val bus = builder().withRabbit().build()

        val failure = assertFailsWith<IllegalStateException> { bus.publish(LifecycleEvent("x")) }

        assertContains(failure.message!!, ".withRedis()")
    }

    @Test
    fun `subscribing without the redis transport fails at freeze, naming the handler`() {
        val bus = builder().withRabbit().build()
        bus.subscribe(LifecycleListener)

        val failure = assertFailsWith<IllegalStateException> { bus.freeze() }

        assertContains(failure.message!!, "LifecycleListener#onEvent")
        assertContains(failure.message!!, ".withRedis()")
    }

    @Test
    fun `rpc without the rabbit transport names the missing builder call`() {
        val bus = builder().withRedis(FakeEventTransport(), FakeQueryTransport()).build()

        val failure = assertFailsWith<IllegalStateException> { bus.rabbit }

        assertContains(failure.message!!, ".withRabbit()")
    }

    @Test
    fun `registration after freeze is rejected`() {
        val bus = builder().withRedis(FakeEventTransport(), FakeQueryTransport()).build()
        bus.freeze()

        assertFailsWith<IllegalStateException> { bus.subscribe(LifecycleListener) }
    }

    @Test
    fun `connect subscribes with the topics of the registry`() = runBlocking {
        val transport = FakeEventTransport()
        val bus = builder().withRedis(transport, FakeQueryTransport()).build()
        bus.subscribe(LifecycleListener)
        bus.freezeAndConnect()

        assertEquals(setOf("lifecycle.test"), transport.exactTopics)
        assertEquals(emptySet(), transport.wildcardPatterns)

        bus.disconnect()
        assertEquals(true, transport.disconnected)
    }

    /**
     * Regression test for H1.
     *
     * `connect()` wraps every rollback step in `runCatching`; `disconnect()` did not, so the
     * first throw leaked the Redis query subscription, the AMQP connection and every consumer
     * and publisher thread behind it. On Paper that happens during shutdown, where it surfaces
     * as a "leaked RabbitClient" warning and a ten-second sleep.
     */
    @Test
    fun `disconnect disconnects every transport even when the first one throws`() = runBlocking {
        val eventTransport = ThrowingEventTransport()
        val queryTransport = FakeQueryTransport()
        val bus = builder().withRedis(eventTransport, queryTransport).build()
        bus.freeze()

        val failure = assertFailsWith<IllegalStateException> { bus.disconnect() }

        assertContains(failure.message!!, "event transport is down")
        assertEquals(true, queryTransport.disconnected, "the query transport must still be closed")
    }

    /**
     * Regression test for H13.
     *
     * `freeze()` produced an excellent error for `@SurfSubscribe` handlers without
     * `.withRedis()`, and no equivalent check for query services: they were stored in the
     * registry, `connect()` skipped subscription entirely, and every query to this process
     * became a silent abstention.
     */
    @Test
    fun `freeze rejects a registered query service without the query transport`() {
        val bus = builder().withRabbit().build()
        bus.registerService(LifecycleLocator::class, LifecycleLocatorImpl)

        val failure = assertFailsWith<IllegalStateException> { bus.freeze() }

        assertContains(failure.message!!, "LifecycleLocator")
        assertContains(failure.message!!, ".withRedis()")
    }

    @Test
    fun `a published event reaches the transport with origin and timestamp stamped`() = runBlocking {
        val transport = FakeEventTransport()
        val bus = builder().withRedis(transport, FakeQueryTransport()).build()
        bus.subscribe(LifecycleListener)
        bus.freezeAndConnect()

        bus.publish(LifecycleEvent("payload"))

        val envelope = transport.published.single().first
        assertEquals("lifecycle.test", envelope.topic)
        assertEquals("lobby-1", envelope.originInstanceId)
        assertEquals(true, envelope.publishedAtEpochMs > 0)

        bus.disconnect()
    }

}

@Serializable
@BusEvent("lifecycle.test")
class LifecycleEvent(val value: String) : SurfBusEvent()

private object LifecycleListener {
    @SurfSubscribe
    fun onEvent(event: LifecycleEvent) = Unit
}

@QueryService
interface LifecycleLocator {
    suspend fun locate(player: String): String?
}

object LifecycleLocatorImpl : LifecycleLocator {
    override suspend fun locate(player: String): String? = null
}
