package dev.slne.surf.rabbitmq.core.retry

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
class FailingPacket(val text: String) : RabbitRequestPacket<FailingResponse>()

@Serializable
class FailingResponse : RabbitResponsePacket()

@Serializable
class OtherPacket(val text: String) : RabbitRequestPacket<FailingResponse>()

/** Handles only [OtherPacket], so a [FailingPacket] delivery finds no handler. */
class OtherHandler {
    @RabbitHandler
    suspend fun onOther(packet: OtherPacket) = Unit
}

@RequiresDocker
class RetryIntegrationTest {

    private val dataPath = Files.createTempDirectory("retry-integration")

    // Not private: handler registration goes through the hidden-class invoker, which
    // rejects inaccessible members.
    class AlwaysFailing {
        val attempts = AtomicInteger()

        @RabbitHandler
        suspend fun onPacket(packet: FailingPacket) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    class NeverRetried {
        val attempts = AtomicInteger()

        @RabbitHandler(retry = false)
        suspend fun onPacket(packet: FailingPacket) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    @Test
    fun `a handler marked retry=false is attempted once and dead-lettered`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("no-retry")
        val handler = NeverRetried()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the handler runs once") { handler.attempts.get() >= 1 }
            delay(3_000)

            assertEquals(
                1, handler.attempts.get(),
                "retry=false must not retry - more than one attempt means the flag is ignored"
            )

            val dlqDepth = messageCount(RabbitTopology.deadLetterQueue(service))
            assertEquals(
                1, dlqDepth,
                "the failed message must be preserved in the dead-letter queue, not dropped"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a retryable handler is attempted again after the first delay`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("retry")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("first attempt") { handler.attempts.get() >= 1 }

            // testConfig shrinks the first tier to 500 ms.
            awaitCondition("second attempt after the first tier", timeoutMillis = 10_000) {
                handler.attempts.get() >= 2
            }

            assertTrue(
                handler.attempts.get() >= 2,
                "the message must be redelivered after the retry TTL expires - if it is " +
                        "not, check that the republish targets the tier exchange with the " +
                        "origin queue as routing key"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a failing handler climbs the full ladder and lands in the DLQ`() = runBlocking {
        // Spec test 6, end to end: first delivery plus three retries, then the DLQ.
        // Feasible only because testConfig shrinks the tiers to 500ms/1s/1.5s.
        val service = RabbitBrokerExtension.uniqueServiceName("full-ladder")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("doomed"), RabbitTarget.ServiceTarget(service))

            awaitCondition("four deliveries in total", timeoutMillis = 20_000) {
                handler.attempts.get() == 4
            }

            awaitCondition("the message reaches the DLQ", timeoutMillis = 10_000) {
                messageCount(RabbitTopology.deadLetterQueue(service)) == 1
            }

            // Give a runaway ladder time to disprove itself.
            delay(3_000)

            assertEquals(
                4, handler.attempts.get(),
                "exactly four deliveries: the first plus three retries - more means the " +
                        "attempt counting from x-death is broken"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a message with no registered handler is dead-lettered, not lost`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("no-handler")

        // The server stays CONNECTED but only handles a different packet type, so the
        // delivery reaches the no-handler branch, which nacks. The service queue's DLX
        // must then preserve the message in the DLQ.
        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(OtherHandler())
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("orphan"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the message lands in the DLQ") {
                messageCount(RabbitTopology.deadLetterQueue(service)) == 1
            }

            assertEquals(
                0, messageCount(RabbitTopology.serviceQueue(service)),
                "the message must leave the service queue via nack, not linger unacked"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    private fun messageCount(queue: String): Int =
        RabbitBrokerExtension.newConnection("depth-check").use { connection ->
            connection.createChannel().use { channel ->
                channel.queueDeclarePassive(queue).messageCount
            }
        }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
