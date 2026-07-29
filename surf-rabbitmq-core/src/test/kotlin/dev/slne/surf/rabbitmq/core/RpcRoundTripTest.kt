package dev.slne.surf.rabbitmq.core

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

@Serializable
class EchoPacket(val text: String) : RabbitRequestPacket<EchoResponse>()

@Serializable
class EchoResponse(val text: String) : RabbitResponsePacket()

@RequiresDocker
class RpcRoundTripTest {

    private val dataPath = Files.createTempDirectory("rpc-test")

    // Not private: handler registration goes through the hidden-class invoker
    // (HANDLER_FACTORY.canAccess), which rejects inaccessible members.
    object EchoHandler {
        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    private fun api(serviceName: String) = SurfRabbitApi
        .builder(serviceName, dataPath)
        .config(testConfig())
        .build()

    @Test
    fun `a request is answered by the hosting service`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("echo")

        val server = api(service)
        server.registerRequestHandler(EchoHandler)
        server.freezeAndConnect()

        val client = api("caller")
        client.freezeAndConnect()

        try {
            val response = client.connection.sendRequest(
                EchoPacket("hello"),
                EchoResponse::class.java,
                RabbitTarget.ServiceTarget(service)
            )

            assertEquals("echo:hello", response.text)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `one client reaches two different services over one connection`() = runBlocking {
        val serviceA = RabbitBrokerExtension.uniqueServiceName("a")
        val serviceB = RabbitBrokerExtension.uniqueServiceName("b")

        val serverA = api(serviceA).also { it.registerRequestHandler(EchoHandler); it.freezeAndConnect() }
        val serverB = api(serviceB).also { it.registerRequestHandler(EchoHandler); it.freezeAndConnect() }

        val client = api("caller")
        client.freezeAndConnect()

        try {
            val fromA = client.connection.sendRequest(
                EchoPacket("a"), EchoResponse::class.java, RabbitTarget.ServiceTarget(serviceA)
            )
            val fromB = client.connection.sendRequest(
                EchoPacket("b"), EchoResponse::class.java, RabbitTarget.ServiceTarget(serviceB)
            )

            assertEquals("echo:a", fromA.text)
            assertEquals("echo:b", fromB.text)
            // Before this redesign, reaching two services required two API instances
            // and therefore two TCP connections.
        } finally {
            client.disconnect()
            serverA.disconnect()
            serverB.disconnect()
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("return listener lands in Plan 4 Task 4 - re-enable there")
    fun `a request to an unknown service fails fast instead of timing out`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            val start = System.currentTimeMillis()

            val thrown = runCatching {
                client.connection.sendRequest(
                    EchoPacket("x"),
                    EchoResponse::class.java,
                    RabbitTarget.ServiceTarget("service-that-does-not-exist")
                )
            }.exceptionOrNull()

            val elapsed = System.currentTimeMillis() - start

            assert(thrown != null) { "an unroutable request must fail" }
            assert(elapsed < 10_000) {
                "expected a fast failure via mandatory + return listener, " +
                        "but it took ${elapsed}ms - it is falling through to the request timeout"
            }
        } finally {
            client.disconnect()
        }
    }
}
