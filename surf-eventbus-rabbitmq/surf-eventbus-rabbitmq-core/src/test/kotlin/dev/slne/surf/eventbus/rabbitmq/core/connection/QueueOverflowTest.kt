package dev.slne.surf.eventbus.rabbitmq.core.connection

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.eventbus.rabbitmq.common.topology.RabbitTopologyDeclarer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Overflow behaviour of a full service queue.
 *
 * `reject-publish` is what turns "a service has been down for days" from a broker-wide memory
 * problem into a local, visible publish failure. The default (`drop-head`) would instead
 * discard the oldest messages silently.
 */
@RequiresDocker
class QueueOverflowTest {

    private lateinit var connection: Connection
    private lateinit var channel: Channel

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("overflow-test")
        channel = connection.createChannel()
        RabbitTopologyDeclarer(channel).declareExchanges()
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `a full queue rejects new publishes instead of dropping old messages`() {
        val service = RabbitBrokerExtension.uniqueServiceName("overflow")
        val queue = RabbitTopology.serviceQueue(service)

        // A tiny bound so the queue fills in a handful of messages.
        channel.queueDeclare(
            queue, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
                "x-max-length" to 5L,
                "x-overflow" to "reject-publish"
            )
        )
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, service)

        channel.confirmSelect()

        val body = "x".repeat(100).toByteArray()
        val properties = AMQP.BasicProperties.Builder().deliveryMode(2).build()

        repeat(5) {
            channel.basicPublish(RabbitTopology.RPC_EXCHANGE, service, properties, body)
        }
        channel.waitForConfirmsOrDie(5_000)

        // Empirically, this broker's quorum queues admit exactly one publish beyond
        // x-max-length before reject-publish engages (confirmed by direct observation: the
        // 6th publish here settles into the queue, and only the 7th is nacked). The bound
        // still holds - the queue never grows without limit - it is off by one from what
        // the RabbitMQ documentation's wording ("once length is reached") suggests literally.
        channel.basicPublish(RabbitTopology.RPC_EXCHANGE, service, properties, body)
        channel.waitForConfirmsOrDie(5_000)

        // This one exceeds even that allowance and must be nacked rather than silently accepted.
        channel.basicPublish(RabbitTopology.RPC_EXCHANGE, service, properties, body)

        val rejected = runCatching { channel.waitForConfirmsOrDie(5_000) }.isFailure

        assertTrue(
            rejected,
            "reject-publish must nack the publisher once the queue is full. If this passes " +
                    "silently, the overflow policy is missing and the oldest messages are " +
                    "being discarded without anyone noticing"
        )

        // waitForConfirmsOrDie closes the channel itself on detecting a nack; check the
        // final depth on a fresh one.
        RabbitBrokerExtension.newConnection("overflow-check").use { checkConnection ->
            checkConnection.createChannel().use { checkChannel ->
                assertTrue(
                    checkChannel.queueDeclarePassive(queue).messageCount <= 6,
                    "the queue must stay within its bound (x-max-length plus this broker's " +
                            "observed one-message overshoot before reject-publish engages)"
                )
            }
        }
    }

    @Test
    fun `the earlier messages survive the rejection`() {
        val service = RabbitBrokerExtension.uniqueServiceName("overflow-keep")
        val queue = RabbitTopology.serviceQueue(service)

        channel.queueDeclare(
            queue, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
                "x-max-length" to 3L,
                "x-overflow" to "reject-publish"
            )
        )
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, service)

        val properties = AMQP.BasicProperties.Builder().deliveryMode(2).build()
        repeat(3) { n ->
            channel.basicPublish(
                RabbitTopology.RPC_EXCHANGE, service, properties, "msg-$n".toByteArray()
            )
        }

        runCatching {
            channel.basicPublish(
                RabbitTopology.RPC_EXCHANGE, service, properties, "overflow".toByteArray()
            )
        }

        Thread.sleep(500)

        // The first message must still be msg-0: reject-publish protects the head of the
        // queue, unlike drop-head which would have discarded it.
        val first = channel.basicGet(queue, true)
        assertTrue(
            first != null && String(first.body) == "msg-0",
            "the oldest message must be preserved, but was ${first?.let { String(it.body) }}"
        )
    }
}
