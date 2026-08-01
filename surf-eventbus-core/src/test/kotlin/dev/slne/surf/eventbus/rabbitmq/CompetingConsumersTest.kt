package dev.slne.surf.eventbus.rabbitmq

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RpcService
interface CompetingEchoService {
    suspend fun echo(text: String): String
}

@RequiresDocker
class CompetingConsumersTest {

    private val dataPath = Files.createTempDirectory("competing-test")

    private class CountingEcho(val id: Int, val seen: MutableMap<String, Int>) : CompetingEchoService {
        val handled = AtomicInteger()

        override suspend fun echo(text: String): String {
            handled.incrementAndGet()
            seen[text] = id
            return "echo:$text"
        }
    }

    @Test
    fun `three instances share the load and each message is handled exactly once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("competing")
        val seen = ConcurrentHashMap<String, Int>()

        val handlers = (1..3).map { CountingEcho(it, seen) }
        val servers = handlers.map { handler ->
            SurfRabbitApi.builder(service, dataPath).config(testConfig()).build().also {
                it.registerService<CompetingEchoService>(handler)
                it.freezeAndConnect()
            }
        }

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<CompetingEchoService>(RabbitTarget.ServiceTarget(service))
            val responses = (1..100).map { n ->
                async { proxy.echo("msg-$n") }
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
