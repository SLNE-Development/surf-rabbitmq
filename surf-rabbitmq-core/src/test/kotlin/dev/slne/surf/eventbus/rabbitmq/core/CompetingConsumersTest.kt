package dev.slne.surf.eventbus.rabbitmq.core

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RequiresDocker
class CompetingConsumersTest {

    private val dataPath = Files.createTempDirectory("competing-test")

    // Not private: registration rejects members the hidden-class invoker cannot access.
    class CountingHandler(val id: Int, val seen: MutableMap<String, Int>) {
        val handled = AtomicInteger()

        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            handled.incrementAndGet()
            seen[packet.text] = id
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    @Test
    fun `three instances share the load and each message is handled exactly once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("competing")
        val seen = ConcurrentHashMap<String, Int>()

        val handlers = (1..3).map { CountingHandler(it, seen) }
        val servers = handlers.map { handler ->
            SurfRabbitApi.builder(service, dataPath).config(testConfig()).build().also {
                it.registerRequestHandler(handler)
                it.freezeAndConnect()
            }
        }

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val responses = (1..100).map { n ->
                async {
                    client.connection.sendRequest(
                        EchoPacket("msg-$n"),
                        EchoResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }.awaitAll()

            assertEquals(100, responses.size)
            assertEquals(
                100, seen.size,
                "every message must be handled exactly once - a smaller number means " +
                        "messages were dropped, a larger one means they were duplicated"
            )
            assertEquals(100, handlers.sumOf { it.handled.get() })

            val idle = handlers.count { it.handled.get() == 0 }
            assertTrue(
                idle == 0,
                "all three instances should receive work, but $idle received none - " +
                        "check that every instance consumes the same service queue"
            )
        } finally {
            client.disconnect()
            servers.forEach { it.disconnect() }
        }
    }
}
