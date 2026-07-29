package dev.slne.surf.rabbitmq.core.retry

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.topology.QueueArguments
import dev.slne.surf.rabbitmq.common.topology.RabbitTopologyDeclarer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RequiresDocker
class RetryQueueTest {

    // Short tiers so the test observes real expiry without waiting ten seconds. Every
    // test in the JVM shares the broker, so they must all use the same values (this is
    // also what testConfig() hands the connection).
    private val testTtls = listOf(500L, 1_000L, 1_500L)

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private lateinit var declarer: RabbitTopologyDeclarer

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("retry-test")
        channel = connection.createChannel()
        declarer = RabbitTopologyDeclarer(channel)
        declarer.declareExchanges()
        declarer.declareRetryTiers(testTtls)
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `a message parked in a retry tier returns to its origin queue`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-return")
        val serviceQueue = declarer.declareServiceQueue(service)

        // Into the tier EXCHANGE, with the origin queue's name as routing key. The fanout
        // ignores the key for insertion; expiry dead-letters to the default exchange,
        // which routes by exactly this key.
        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            serviceQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "retry-me".toByteArray()
        )

        // The message must not be in the service queue yet.
        assertEquals(
            null, channel.basicGet(serviceQueue, true),
            "the message should still be held in the retry tier"
        )

        val returned = awaitMessage(serviceQueue, timeoutMillis = 10_000)
        assertEquals(
            "retry-me", String(returned),
            "expiry must route the message back to its origin queue via the default " +
                    "exchange - if this fails, check the tier's x-dead-letter-exchange " +
                    "and the routing key of the republish"
        )
    }

    @Test
    fun `the same tier serves a shared event queue`() {
        // The reason the tiers dead-letter to the default exchange instead of surf.rpc:
        // event queues are fed by topic bindings surf.rpc knows nothing about.
        val service = RabbitBrokerExtension.uniqueServiceName("retry-event")
        val eventQueue = declarer.declareSharedEventQueue(service, setOf("test.retry.#"))

        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            eventQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "event-retry".toByteArray()
        )

        val returned = awaitMessage(eventQueue, timeoutMillis = 10_000)
        assertEquals("event-retry", String(returned))
    }

    @Test
    fun `a returned message carries an incremented attempt count`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-count")
        val serviceQueue = declarer.declareServiceQueue(service)

        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            serviceQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "counted".toByteArray()
        )

        val response = awaitDelivery(serviceQueue, timeoutMillis = 10_000)
        val attempts = RetryPolicy.attemptsFrom(response.props.headers)

        assertTrue(
            attempts >= 1,
            "x-death must record the expiry so the ladder can advance, but attempts=$attempts"
        )
    }

    @Test
    fun `declaring the tiers again with the same ttls succeeds`() {
        // Redeclaring with identical arguments succeeds; differing ones fail the channel.
        // This is why every process on a broker must agree on getRetryTtlMillis().
        RetryTier.entries.forEachIndexed { index, tier ->
            val ok = channel.queueDeclare(
                tier.queueName, true, false, false, QueueArguments.retryQueue(testTtls[index])
            )
            assertNotNull(ok)
        }
    }

    @Test
    fun `retry queues do not pin the routing key`() {
        assertEquals(
            null, QueueArguments.retryQueue(500L)["x-dead-letter-routing-key"],
            "the preserved per-message key IS the return routing; pinning it would send " +
                    "every retried message of every service to one queue"
        )
    }

    private fun awaitMessage(queue: String, timeoutMillis: Long): ByteArray =
        awaitDelivery(queue, timeoutMillis).body

    private fun awaitDelivery(queue: String, timeoutMillis: Long): com.rabbitmq.client.GetResponse {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val response = channel.basicGet(queue, true)
            if (response != null) return response
            Thread.sleep(50)
        }

        throw AssertionError("no message arrived in '$queue' within ${timeoutMillis}ms")
    }
}
