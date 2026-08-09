package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.redis.SurfRedisApi
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.eventbus.transport.EventEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.redisson.client.codec.StringCodec
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
    private suspend fun newApi(): SurfRedisApi {
        val uri = RedisURI("redis://${redis.host}:${redis.getMappedPort(6379)}")
        val api = SurfRedisApi.create(uri)
        api.freezeAndConnect()
        apis += api
        return api
    }

    private suspend fun newTransport(): RedisEventTransport {
        val api = newApi()
        return RedisEventTransport(api, api.json)
    }

    @Test
    fun `three instances receive a broadcast event`() =
        runTest(timeout = 10.seconds) {
            val received = List(3) { Channel<EventEnvelope>(capacity = 1) }
            val transports =
                received.map { channel ->
                    newTransport().apply {
                        connect(setOf("faction.disbanded"), emptySet()) { envelope, _ ->
                            channel.send(
                                envelope,
                            )
                        }
                    }
                }

            transports.first().publish(envelope("faction.disbanded"), null)

            received.forEach { channel -> assertEquals("faction.disbanded", channel.receive().topic) }
        }

    @Test
    fun `an exact topic only reaches a subscriber for that topic`() =
        runTest(timeout = 10.seconds) {
            val matched = Channel<EventEnvelope>(capacity = 1)
            val unrelated = Channel<EventEnvelope>(capacity = 1)

            newTransport().connect(
                setOf("faction.disbanded"),
                emptySet(),
            ) { e, _ -> matched.send(e) }
            val other = newTransport()
            other.connect(setOf("player.joined"), emptySet()) { e, _ -> unrelated.send(e) }

            newTransport().publish(envelope("faction.disbanded"), null)

            assertEquals("faction.disbanded", matched.receive().topic)
            assertNull(withTimeoutOrNullSeconds(unrelated))
        }

    @Test
    fun `a single-segment wildcard matches one segment`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<EventEnvelope>(capacity = 1)
            newTransport().connect(emptySet(), setOf("faction.*")) { e, _ -> channel.send(e) }

            newTransport().publish(envelope("faction.disbanded"), null)

            assertEquals("faction.disbanded", channel.receive().topic)
        }

    @Test
    fun `a hash wildcard matches zero or more segments`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<EventEnvelope>(capacity = 1)
            newTransport().connect(emptySet(), setOf("faction.#")) { e, _ -> channel.send(e) }

            newTransport().publish(envelope("faction.member.kicked"), null)

            assertEquals("faction.member.kicked", channel.receive().topic)
        }

    @Test
    fun `two overlapping patterns both fire`() =
        runTest(timeout = 10.seconds) {
            val first = Channel<EventEnvelope>(capacity = 1)
            val second = Channel<EventEnvelope>(capacity = 1)
            newTransport().connect(emptySet(), setOf("faction.*")) { e, _ -> first.send(e) }
            newTransport().connect(emptySet(), setOf("faction.#")) { e, _ -> second.send(e) }

            newTransport().publish(envelope("faction.disbanded"), null)

            assertEquals("faction.disbanded", first.receive().topic)
            assertEquals("faction.disbanded", second.receive().topic)
        }

    @Test
    fun `a codec event arrives on the binary channel`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<ByteArray?>(capacity = 1)
            newTransport().connect(setOf("faction.disbanded"), emptySet()) { _, payload ->
                channel.send(
                    payload,
                )
            }

            newTransport().publish(envelope("faction.disbanded"), byteArrayOf(1, 2, 3))

            assertEquals(listOf<Byte>(1, 2, 3), channel.receive()?.toList())
        }

    @Test
    fun `a wildcard subscription receives both JSON and codec events`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<ByteArray?>(capacity = 2)
            newTransport().connect(emptySet(), setOf("faction.#")) { _, payload ->
                channel.send(
                    payload,
                )
            }

            val publisher = newTransport()
            publisher.publish(envelope("faction.disbanded"), null)
            publisher.publish(envelope("faction.renamed"), byteArrayOf(9))

            assertNull(channel.receive())
            assertEquals(listOf<Byte>(9), channel.receive()?.toList())
        }

    /**
     * Regression test for C3.
     *
     * `disconnect()` used to dispose the `Disposable` returned by `.subscribe()`. That
     * subscription has already completed - it delivered the listener id - so disposing it is a
     * no-op and the Redis listener stayed attached for the life of the `RedisApi`. A
     * disconnected bus went on dispatching into a scope that was about to be cancelled, and in
     * tests a bus from an earlier case answered a later one.
     */
    @Test
    fun `disconnect removes the listeners, so no later event is delivered`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<EventEnvelope>(capacity = 1)
            val subscriber = newTransport()
            subscriber.connect(setOf("faction.disbanded"), emptySet()) { e, _ -> channel.send(e) }

            subscriber.disconnect()
            newTransport().publish(envelope("faction.disbanded"), null)

            assertNull(
                withTimeoutOrNullSeconds(channel),
                "a disconnected transport must not receive",
            )
        }

    /** The same, for the pattern subscriptions, which take a different Redisson code path. */
    @Test
    fun `disconnect removes the pattern listeners too`() =
        runTest(timeout = 10.seconds) {
            val channel = Channel<EventEnvelope>(capacity = 1)
            val subscriber = newTransport()
            subscriber.connect(emptySet(), setOf("faction.#")) { e, _ -> channel.send(e) }

            subscriber.disconnect()
            newTransport().publish(envelope("faction.member.kicked"), null)

            assertNull(withTimeoutOrNullSeconds(channel), "a disconnected transport must not receive")
        }

    /**
     * Regression test for C3, second half.
     *
     * `connect()` fired off the SUBSCRIBE without awaiting it, so an event published
     * immediately afterwards could arrive before the subscription existed - and Pub/Sub has no
     * redelivery, so it was gone for good. The sibling `RedisQueryTransport` awaits its
     * listener id for exactly this reason.
     *
     * Asserted structurally rather than by racing a publish against it: the broker's own
     * subscriber count is the thing `connect()` is supposed to have established, and a timing
     * test that merely *usually* wins tells us nothing on a fast loopback.
     */
    @Test
    fun `connect does not return before the subscription exists`() =
        runTest(timeout = 20.seconds) {
            val api = newApi()
            val transport = RedisEventTransport(api, api.json)

            transport.connect(setOf("faction.awaited"), emptySet()) { _, _ -> }

            val subscribers =
                api.redisson
                    .getTopic(RedisChannels.json("faction.awaited"), StringCodec.INSTANCE)
                    .countSubscribers()

            assertEquals(
                1,
                subscribers,
                "connect() returned while the SUBSCRIBE was still in flight; an event published " +
                    "now would be lost, because Pub/Sub has no redelivery",
            )
        }

    /**
     * Waits up to a second of **real** time for a delivery.
     *
     * `runTest` drives a virtual clock, so a plain `withTimeout` here expires instantly without
     * ever giving Redis a chance to deliver - which makes an `assertNull` pass no matter what
     * the transport does. Hopping to a real dispatcher is what makes the negative assertions in
     * this file mean anything.
     */
    private suspend fun withTimeoutOrNullSeconds(channel: Channel<EventEnvelope>): EventEnvelope? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(1.seconds) { channel.receive() }
        }

    private fun envelope(topic: String) =
        EventEnvelope(
            topic = topic,
            type = "dev.example.Test",
            originInstanceId = "test-instance",
            publishedAtEpochMs = System.currentTimeMillis(),
            payload = "{}",
        )

    companion object {
        private lateinit var redis: GenericContainer<*>
        private val apis = mutableListOf<SurfRedisApi>()

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() =
            kotlinx.coroutines.runBlocking {
                apis.forEach { runCatching { it.disconnect() } }
                redis.stop()
            }
    }
}
