package dev.slne.surf.eventbus.rabbitmq.packet

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

@RpcService
interface EchoLargeService {
    suspend fun echo(payload: String): String
}

@RequiresDocker
class ChunkingTest {

    private val dataPath = Files.createTempDirectory("chunking-test")

    private object EchoLargeImpl : EchoLargeService {
        override suspend fun echo(payload: String): String = payload
    }

    @Test
    fun `a payload larger than one chunk survives the round trip`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<EchoLargeService>(EchoLargeImpl)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            // Comfortably beyond the chunk threshold so splitting is exercised.
            val payload = buildString { repeat(2_000_000) { append('x') } }

            val response = client.rpc<EchoLargeService>(RabbitTarget.ServiceTarget(service))
                .echo(payload)

            assertEquals(
                payload.length, response.length,
                "a truncated payload means chunks were dropped or reassembled out of order"
            )
            assertEquals(payload, response)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a chunked request also survives the round trip`() = runBlocking {
        // testConfig() defaults to requestChunking = false (only the response path is
        // chunked by default); this exercises the request-side chunking path too.
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-request")

        val server = SurfRabbitApi.builder(service, dataPath)
            .config(testConfig(requestChunking = true))
            .build()
        server.registerService<EchoLargeService>(EchoLargeImpl)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath)
            .config(testConfig(requestChunking = true))
            .build()
        client.freezeAndConnect()

        try {
            val payload = buildString { repeat(2_000_000) { append('y') } }

            val response = client.rpc<EchoLargeService>(RabbitTarget.ServiceTarget(service))
                .echo(payload)

            assertEquals(payload, response)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `many concurrent large payloads do not interleave`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-concurrent")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<EchoLargeService>(EchoLargeImpl)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<EchoLargeService>(RabbitTarget.ServiceTarget(service))
            val responses = (1..5).map { n ->
                async {
                    val payload = n.toString().repeat(500_000)
                    val response = proxy.echo(payload)
                    payload to response
                }
            }.map { it.await() }

            responses.forEach { (sent, received) ->
                assertEquals(
                    sent, received,
                    "mismatched payloads mean chunks of different messages were mixed up"
                )
            }
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }
}
