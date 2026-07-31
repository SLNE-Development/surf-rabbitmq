package dev.slne.surf.eventbus.rabbitmq.core.packet

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

@Serializable
class LargePacket(val payload: String) : RabbitRequestPacket<LargeResponse>()

@Serializable
class LargeResponse(val payload: String) : RabbitResponsePacket()

@RequiresDocker
class ChunkingTest {

    private val dataPath = Files.createTempDirectory("chunking-test")

    private object EchoLarge {
        @RabbitHandler
        suspend fun onLarge(packet: LargePacket) {
            packet.respond(LargeResponse(packet.payload))
        }
    }

    @Test
    fun `a payload larger than one chunk survives the round trip`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(EchoLarge)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            // Comfortably beyond the chunk threshold so splitting is exercised.
            val payload = buildString { repeat(2_000_000) { append('x') } }

            val response = client.connection.sendRequest(
                LargePacket(payload), LargeResponse::class.java, RabbitTarget.ServiceTarget(service)
            )

            assertEquals(
                payload.length, response.payload.length,
                "a truncated payload means chunks were dropped or reassembled out of order"
            )
            assertEquals(payload, response.payload)
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
        server.registerRequestHandler(EchoLarge)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath)
            .config(testConfig(requestChunking = true))
            .build()
        client.freezeAndConnect()

        try {
            val payload = buildString { repeat(2_000_000) { append('y') } }

            val response = client.connection.sendRequest(
                LargePacket(payload), LargeResponse::class.java, RabbitTarget.ServiceTarget(service)
            )

            assertEquals(payload, response.payload)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `many concurrent large payloads do not interleave`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-concurrent")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(EchoLarge)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val responses = (1..5).map { n ->
                async {
                    val payload = n.toString().repeat(500_000)
                    val response = client.connection.sendRequest(
                        LargePacket(payload),
                        LargeResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                    payload to response.payload
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
