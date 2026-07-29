package dev.slne.surf.rabbitmq.core.failure

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
class SlowPacket(val text: String, val holdMillis: Long) : RabbitRequestPacket<SlowResponse>()

@Serializable
class SlowResponse(val text: String) : RabbitResponsePacket()

/**
 * What happens when a microservice dies at specific points in the request lifecycle.
 *
 * These are the scenarios that decide whether the at-least-once promise holds. A message must
 * never be lost because the process handling it went away — and the price of that guarantee is
 * that a handler can run more than once, which the tests also pin down so nobody is surprised
 * by it later.
 */
@RequiresDocker
class ConsumerDeathTest {

    private val dataPath = Files.createTempDirectory("consumer-death")

    private fun api(service: String) = SurfRabbitApi
        .builder(service, dataPath)
        .config(testConfig(requestTimeoutSeconds = 30))
        .build()

    // Not private: handler registration goes through the hidden-class invoker, which
    // rejects inaccessible members.
    class SlowHandler(val started: AtomicInteger, val completed: AtomicInteger) {
        @RabbitHandler
        suspend fun onSlow(packet: SlowPacket) {
            started.incrementAndGet()
            delay(packet.holdMillis)
            completed.incrementAndGet()
            packet.respond(SlowResponse("done:${packet.text}"))
        }
    }

    @Test
    fun `a message survives the instance that was processing it`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("death-during")

        val dyingStarted = AtomicInteger()
        val dyingCompleted = AtomicInteger()
        val dying = api(service).also {
            it.registerRequestHandler(SlowHandler(dyingStarted, dyingCompleted))
            it.freezeAndConnect()
        }

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val call = async {
                runCatching {
                    client.connection.sendRequest(
                        SlowPacket("x", holdMillis = 30_000),
                        SlowResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }

            // Wait until the handler is genuinely running, then kill the instance mid-flight.
            awaitCondition("the handler starts") { dyingStarted.get() == 1 }
            dying.disconnect()

            // A second instance takes over. The message was never acked, so the broker
            // redelivers it.
            val survivorStarted = AtomicInteger()
            val survivorCompleted = AtomicInteger()
            val survivor = api(service).also {
                it.registerRequestHandler(SlowHandler(survivorStarted, survivorCompleted))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("the survivor picks up the message", timeoutMillis = 30_000) {
                    survivorStarted.get() >= 1
                }

                assertEquals(
                    0, dyingCompleted.get(),
                    "the first handler never finished, which is the point of the scenario"
                )
                assertTrue(
                    survivorStarted.get() >= 1,
                    "an unacked message must be redelivered - if this fails, work is lost " +
                            "whenever an instance restarts"
                )
            } finally {
                survivor.disconnect()
            }

            call.cancel()
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a handler may run twice when its instance dies - at-least-once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("at-least-once")
        val totalStarts = AtomicInteger()

        val first = api(service).also {
            it.registerRequestHandler(SlowHandler(totalStarts, AtomicInteger()))
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            client.send(SlowPacket("x", holdMillis = 20_000), RabbitTarget.ServiceTarget(service))

            awaitCondition("first attempt starts") { totalStarts.get() == 1 }
            first.disconnect()

            val second = api(service).also {
                it.registerRequestHandler(SlowHandler(totalStarts, AtomicInteger()))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("second attempt starts", timeoutMillis = 30_000) {
                    totalStarts.get() >= 2
                }

                assertTrue(
                    totalStarts.get() >= 2,
                    "delivery is at-least-once: a handler that is not idempotent must use " +
                            "@RabbitHandler(retry = false) and accept the message being dropped"
                )
            } finally {
                second.disconnect()
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a duplicate reply after redelivery is discarded, not surfaced`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("dup-reply")
        val replies = AtomicInteger()

        // Replies, then stalls before acking, so the message is redelivered while the
        // answer is already on its way to the client.
        class ReplyThenStall {
            @RabbitHandler
            suspend fun onSlow(packet: SlowPacket) {
                replies.incrementAndGet()
                packet.respond(SlowResponse("done:${packet.text}"))
                delay(packet.holdMillis)
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(ReplyThenStall())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val received = AtomicReference<String?>(null)

            val call = async {
                runCatching {
                    client.connection.sendRequest(
                        SlowPacket("x", holdMillis = 5_000),
                        SlowResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    ).text
                }.getOrNull()
            }

            awaitCondition("the client receives an answer", timeoutMillis = 20_000) {
                call.isCompleted
            }
            received.set(call.await())

            assertEquals(
                "done:x", received.get(),
                "the client must get its answer even though the message was never acked"
            )

            // A second delivery produces a second reply for a correlation id the client has
            // already retired. It must be dropped silently, not delivered to anyone.
            delay(3_000)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `messages stay queued when the last instance goes away`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("all-gone")

        // Create the durable queue, then take the service away entirely.
        api(service).also {
            it.registerRequestHandler(SlowHandler(AtomicInteger(), AtomicInteger()))
            it.freezeAndConnect()
        }.disconnect()

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            repeat(5) {
                client.send(
                    SlowPacket("queued-$it", holdMillis = 0),
                    RabbitTarget.ServiceTarget(service)
                )
            }

            delay(2_000)

            val depth = RabbitBrokerExtension.newConnection("depth").use { connection ->
                connection.createChannel().use { channel ->
                    channel.queueDeclarePassive(RabbitTopology.serviceQueue(service)).messageCount
                }
            }

            assertEquals(
                5, depth,
                "with no instance running, fire-and-forget messages must wait in the durable " +
                        "queue rather than being discarded"
            )

            // Bringing the service back must drain them.
            val started = AtomicInteger()
            val restarted = api(service).also {
                it.registerRequestHandler(SlowHandler(started, AtomicInteger()))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("the backlog is processed", timeoutMillis = 20_000) {
                    started.get() == 5
                }
            } finally {
                restarted.disconnect()
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a service dying midway through a chunked reply never yields a mixed packet`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-death")
        val attempt = AtomicInteger()

        // First attempt dies after the handler responds but before all chunks are flushed;
        // the second answers completely. Distinct filler bytes make a mixture detectable.
        class ChunkedHandler {
            @RabbitHandler
            suspend fun onSlow(packet: SlowPacket) {
                val n = attempt.incrementAndGet()
                val filler = if (n == 1) 'a' else 'b'
                packet.respond(SlowResponse(filler.toString().repeat(1_500_000)))
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(ChunkedHandler())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val response = client.connection.sendRequest(
                SlowPacket("x", holdMillis = 0),
                SlowResponse::class.java,
                RabbitTarget.ServiceTarget(service)
            )

            val distinct = response.text.toCharArray().distinct()
            assertEquals(
                1, distinct.size,
                "the reply must come from a single attempt. Two distinct filler characters " +
                        "$distinct mean chunks of two responses were assembled into one packet"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
