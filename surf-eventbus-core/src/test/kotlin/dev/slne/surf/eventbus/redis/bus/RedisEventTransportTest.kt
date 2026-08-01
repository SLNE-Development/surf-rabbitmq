package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.transport.EventEnvelope
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.testing.RequiresDocker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.redisson.misc.RedisURI
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The event-suite cases that need a real broker: broadcast delivery, exact-topic isolation,
 * `*`/`#` wildcard matching, overlapping patterns, and the binary codec channel.
 */
@RequiresDocker
class RedisEventTransportTest {

    private suspend fun newTransport(): RedisEventTransport {
        val uri = RedisURI("redis://${redis.host}:${redis.getMappedPort(6379)}")
        val api = RedisApi.create(uri).freezeAndConnect()
        apis += api
        return RedisEventTransport(api, api.json)
    }

    @Test
    fun `three instances receive a broadcast event`() = runTest(timeout = 10.seconds) {
        val received = List(3) { Channel<EventEnvelope>(capacity = 1) }
        val transports = received.map { channel ->
            newTransport().apply {
                connect(setOf("faction.disbanded"), emptySet()) { envelope, _ -> channel.send(envelope) }
            }
        }

        transports.first().publish(envelope("faction.disbanded"), null)

        received.forEach { channel -> assertEquals("faction.disbanded", channel.receive().topic) }
    }

    @Test
    fun `an exact topic only reaches a subscriber for that topic`() = runTest(timeout = 10.seconds) {
        val matched = Channel<EventEnvelope>(capacity = 1)
        val unrelated = Channel<EventEnvelope>(capacity = 1)

        newTransport().connect(setOf("faction.disbanded"), emptySet()) { e, _ -> matched.send(e) }
        val other = newTransport()
        other.connect(setOf("player.joined"), emptySet()) { e, _ -> unrelated.send(e) }

        newTransport().publish(envelope("faction.disbanded"), null)

        assertEquals("faction.disbanded", matched.receive().topic)
        assertNull(withTimeoutOrNullSeconds(unrelated))
    }

    @Test
    fun `a single-segment wildcard matches one segment`() = runTest(timeout = 10.seconds) {
        val channel = Channel<EventEnvelope>(capacity = 1)
        newTransport().connect(emptySet(), setOf("faction.*")) { e, _ -> channel.send(e) }

        newTransport().publish(envelope("faction.disbanded"), null)

        assertEquals("faction.disbanded", channel.receive().topic)
    }

    @Test
    fun `a hash wildcard matches zero or more segments`() = runTest(timeout = 10.seconds) {
        val channel = Channel<EventEnvelope>(capacity = 1)
        newTransport().connect(emptySet(), setOf("faction.#")) { e, _ -> channel.send(e) }

        newTransport().publish(envelope("faction.member.kicked"), null)

        assertEquals("faction.member.kicked", channel.receive().topic)
    }

    @Test
    fun `two overlapping patterns both fire`() = runTest(timeout = 10.seconds) {
        val first = Channel<EventEnvelope>(capacity = 1)
        val second = Channel<EventEnvelope>(capacity = 1)
        newTransport().connect(emptySet(), setOf("faction.*")) { e, _ -> first.send(e) }
        newTransport().connect(emptySet(), setOf("faction.#")) { e, _ -> second.send(e) }

        newTransport().publish(envelope("faction.disbanded"), null)

        assertEquals("faction.disbanded", first.receive().topic)
        assertEquals("faction.disbanded", second.receive().topic)
    }

    @Test
    fun `a codec event arrives on the binary channel`() = runTest(timeout = 10.seconds) {
        val channel = Channel<ByteArray?>(capacity = 1)
        newTransport().connect(setOf("faction.disbanded"), emptySet()) { _, payload -> channel.send(payload) }

        newTransport().publish(envelope("faction.disbanded"), byteArrayOf(1, 2, 3))

        assertEquals(listOf<Byte>(1, 2, 3), channel.receive()?.toList())
    }

    @Test
    fun `a wildcard subscription receives both JSON and codec events`() = runTest(timeout = 10.seconds) {
        val channel = Channel<ByteArray?>(capacity = 2)
        newTransport().connect(emptySet(), setOf("faction.#")) { _, payload -> channel.send(payload) }

        val publisher = newTransport()
        publisher.publish(envelope("faction.disbanded"), null)
        publisher.publish(envelope("faction.renamed"), byteArrayOf(9))

        assertNull(channel.receive())
        assertEquals(listOf<Byte>(9), channel.receive()?.toList())
    }

    private suspend fun withTimeoutOrNullSeconds(channel: Channel<EventEnvelope>): EventEnvelope? =
        try {
            withTimeout(1.seconds) { channel.receive() }
        } catch (_: Exception) {
            null
        }

    private fun envelope(topic: String) = EventEnvelope(
        topic = topic,
        type = "dev.example.Test",
        originInstanceId = "test-instance",
        publishedAtEpochMs = System.currentTimeMillis(),
        payload = "{}"
    )

    companion object {
        private lateinit var redis: GenericContainer<*>
        private val apis = mutableListOf<RedisApi>()

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() = kotlinx.coroutines.runBlocking {
            apis.forEach { runCatching { it.disconnect() } }
            redis.stop()
        }
    }
}
