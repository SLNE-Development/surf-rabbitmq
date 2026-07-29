package dev.slne.surf.rabbitmq.core.connection

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.core.EchoPacket
import dev.slne.surf.rabbitmq.core.EchoResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovery after the connection drops.
 *
 * The reply queue is `autoDelete` with a STABLE name (`surf.reply.<instanceId>`): it dies
 * with the connection and topology recovery re-declares it under the same name. The danger
 * is no longer a stale queue name — it is `replyEndpoint` never being repopulated, because
 * the old signaling was keyed to queue *renames* that stable names never trigger. If the
 * endpoint stays null, every post-recovery RPC waits on a reply path that no longer exists.
 */
@RequiresDocker
class BrokerRestartTest {

    private val dataPath = Files.createTempDirectory("restart-test")

    private object EchoHandler {
        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 20)).build()

    @Test
    fun `rpc works again after the connection is dropped and recovered`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("restart")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            assertEquals(
                "echo:before",
                client.connection.sendRequest(
                    EchoPacket("before"), EchoResponse::class.java, RabbitTarget.ServiceTarget(service)
                ).text
            )

            // Kill the underlying connections and let automatic recovery rebuild them.
            RabbitBrokerExtension.closeAllConnections()

            val recovered = withTimeoutOrNull(60_000) {
                while (true) {
                    val result = runCatching {
                        client.connection.sendRequest(
                            EchoPacket("after"),
                            EchoResponse::class.java,
                            RabbitTarget.ServiceTarget(service)
                        ).text
                    }.getOrNull()

                    if (result != null) return@withTimeoutOrNull result
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") null
            }

            assertEquals(
                "echo:after", recovered,
                "after recovery the client must publish and consume again - if this times " +
                        "out, replyEndpoint was never repopulated by onRecoveryCompleted"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `requests in flight during a drop do not hang forever`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("inflight")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()

            val outcome = async {
                runCatching {
                    client.connection.sendRequest(
                        EchoPacket("in-flight"),
                        EchoResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }

            delay(50)
            RabbitBrokerExtension.closeAllConnections()

            outcome.await()
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 40_000,
                "a request interrupted by a connection loss must settle - either completing " +
                        "after recovery or failing - but it took ${elapsed}ms"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }
}
