package dev.slne.surf.eventbus.rabbitmq.common.topology

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RequiresDocker
class RabbitTopologyDeclarerTest {

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private lateinit var declarer: RabbitTopologyDeclarer

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("declarer-test")
        channel = connection.createChannel()
        declarer = RabbitTopologyDeclarer(channel)
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `declaring exchanges is idempotent`() {
        declarer.declareExchanges()
        declarer.declareExchanges()

        // Passive declare throws if the exchange is absent.
        channel.exchangeDeclarePassive(RabbitTopology.RPC_EXCHANGE)
        channel.exchangeDeclarePassive(RabbitTopology.EVENTS_EXCHANGE)
        channel.exchangeDeclarePassive(RabbitTopology.DLX_EXCHANGE)
    }

    @Test
    fun `a service queue is bound to the rpc exchange under its service name`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("svc")
        val queue = declarer.declareServiceQueue(service)

        assertEquals(RabbitTopology.serviceQueue(service), queue)

        channel.basicPublish(
            RabbitTopology.RPC_EXCHANGE,
            service,
            null,
            "hello".toByteArray()
        )

        val delivered = awaitMessage(queue)
        assertEquals("hello", String(delivered))
    }

    @Test
    fun `a mandatory publish to an unknown service is returned to the publisher`() {
        declarer.declareExchanges()

        // This is the property fail-fast is built on in Plan 4. It only holds because
        // surf.rpc has NO alternate exchange: an AE would route the message and
        // basic.return would never fire.
        val returned = java.util.concurrent.CompletableFuture<String>()
        channel.addReturnListener { _, _, _, _, _, body ->
            returned.complete(String(body))
        }

        channel.basicPublish(
            RabbitTopology.RPC_EXCHANGE,
            "service-that-does-not-exist",
            /* mandatory = */ true,
            null,
            "orphan".toByteArray()
        )

        assertEquals(
            "orphan",
            returned.get(5, java.util.concurrent.TimeUnit.SECONDS),
            "an unroutable mandatory publish must come back via basic.return"
        )
    }

    @Test
    fun `the unroutable audit queue is declared unbound and redeclarable`() {
        val queue = declarer.declareUnroutableQueue()

        assertEquals(RabbitTopology.UNROUTABLE_QUEUE, queue)

        // Identical redeclaration must succeed - every process declares this queue.
        assertNotNull(
            channel.queueDeclare(queue, true, false, false, QueueArguments.deadLetterQueue())
        )
    }

    @Test
    fun `an instance queue only receives messages for its own instance`() {
        declarer.declareExchanges()
        val a = RabbitBrokerExtension.uniqueServiceName("inst-a")
        val b = RabbitBrokerExtension.uniqueServiceName("inst-b")

        val queueA = declarer.declareInstanceQueue(a)
        declarer.declareInstanceQueue(b)

        channel.basicPublish(RabbitTopology.RPC_EXCHANGE, a, null, "for-a".toByteArray())

        assertEquals("for-a", String(awaitMessage(queueA)))
        assertEquals(
            0, channel.queueDeclarePassive(RabbitTopology.instanceQueue(b)).messageCount,
            "instance b must not receive a message addressed to instance a"
        )
    }

    @Test
    fun `redeclaring a service queue with different arguments is refused`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("conflict")
        declarer.declareServiceQueue(service)

        // A second channel, because a failed declare kills the channel it ran on.
        val other = connection.createChannel()
        try {
            val thrown = runCatching {
                other.queueDeclare(
                    RabbitTopology.serviceQueue(service),
                    true, false, false,
                    mapOf("x-queue-type" to "classic")
                )
            }.exceptionOrNull()

            assertTrue(
                thrown is IOException,
                "changing queue arguments must be refused, otherwise two versions of the " +
                        "library would silently disagree about the topology"
            )
        } finally {
            // The protocol violation above already closed the channel; closing it again
            // would throw AlreadyClosedException.
            runCatching { other.close() }
        }
    }

    @Test
    fun `the service queue carries the configured arguments`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("args")
        declarer.declareServiceQueue(service)

        // Redeclaring with identical arguments succeeds; with different ones it would fail.
        // This is the cheapest way to assert the stored arguments without the HTTP API.
        val ok = channel.queueDeclare(
            RabbitTopology.serviceQueue(service),
            true, false, false,
            QueueArguments.serviceQueue()
        )

        assertNotNull(ok)
    }

    @Test
    fun `a reply queue is addressable through the default exchange`() {
        declarer.declareExchanges()
        val instance = RabbitBrokerExtension.uniqueServiceName("reply")
        val queue = declarer.declareReplyQueue(instance)

        channel.basicPublish("", queue, null, "pong".toByteArray())

        assertEquals("pong", String(awaitMessage(queue)))
    }

    private fun awaitMessage(queue: String, timeoutMillis: Long = 5_000): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val response = channel.basicGet(queue, true)
            if (response != null) return response.body
            Thread.sleep(25)
        }

        throw AssertionError("no message arrived in '$queue' within ${timeoutMillis}ms")
    }
}
