package dev.slne.surf.rabbitmq.core.send

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
class WorkPacket(val text: String) : RabbitRequestPacket<WorkResponse>()

@Serializable
class WorkResponse : RabbitResponsePacket()

@RequiresDocker
class FireAndForgetTest {

    private val dataPath = Files.createTempDirectory("fnf-test")

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 3)).build()

    @Test
    fun `a handler that never responds is acked, not timed out`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf")
        val handled = AtomicInteger()

        // The natural F&F handler shape: process the message, respond to nobody.
        class SilentHandler {
            @RabbitHandler
            suspend fun onWork(packet: WorkPacket) {
                handled.incrementAndGet()
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(SilentHandler())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            client.send(WorkPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the handler runs") { handled.get() == 1 }

            // Longer than the 3s request timeout: with the broken await-respond() flow the
            // message would be nacked around now and, later, retried.
            delay(5_000)

            assertEquals(
                1, handled.get(),
                "a successful F&F handler must be acked on completion - a second run means " +
                        "it was nacked and redelivered despite succeeding"
            )
            assertEquals(
                0, messageCount(RabbitTopology.serviceQueue(service)),
                "the message must be gone from the queue after the ack"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a respond() from a fire-and-forget handler is discarded without error`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf-respond")
        val handled = AtomicInteger()

        class RespondingHandler {
            @RabbitHandler
            suspend fun onWork(packet: WorkPacket) {
                handled.incrementAndGet()
                packet.respond(WorkResponse())
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(RespondingHandler())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            client.send(WorkPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the handler runs") { handled.get() == 1 }
            delay(1_000)

            assertEquals(1, handled.get(), "respond() on F&F must be a no-op, not a failure")
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
            while (!condition()) delay(50)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
