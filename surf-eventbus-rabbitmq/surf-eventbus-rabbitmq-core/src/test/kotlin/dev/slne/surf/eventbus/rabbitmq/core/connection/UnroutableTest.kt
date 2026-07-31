package dev.slne.surf.eventbus.rabbitmq.core.connection

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.testing.testConfig
import dev.slne.surf.eventbus.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.eventbus.rabbitmq.core.rpc.EchoRpcService
import dev.slne.surf.eventbus.rabbitmq.core.send.WorkService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RequiresDocker
class UnroutableTest {

    private val dataPath = Files.createTempDirectory("unroutable-test")

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 30)).build()

    @Test
    fun `a request to an unknown service fails fast, not after the timeout`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            val start = System.currentTimeMillis()

            assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget("nonexistent-${System.nanoTime()}"))
                    .echo("x")
            }

            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 5_000,
                "the return listener should fail this in milliseconds, but it took ${elapsed}ms " +
                        "- it is falling through to the 30s request timeout"
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `the exception names the target that could not be reached`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()
        val target = "nonexistent-${System.nanoTime()}"

        try {
            val thrown = assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget(target)).echo("x")
            }

            assertTrue(thrown.target == target, "the message must name the target to be useful")
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `an unroutable message is also preserved in the unroutable queue`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            runCatching {
                client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget("nonexistent-${System.nanoTime()}"))
                    .echo("x")
            }

            delay(1_000)

            val depth = RabbitBrokerExtension.newConnection("depth").use { connection ->
                connection.createChannel().use { channel ->
                    channel.queueDeclarePassive(RabbitTopology.UNROUTABLE_QUEUE).messageCount
                }
            }

            assertTrue(
                depth >= 1,
                "the return listener must republish a copy so a misrouted message can be diagnosed"
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a fire-and-forget to an unknown service fails fast too`() = runBlocking {
        // Fire-and-forget has no reply to wait for, so without this check a call to a
        // misspelled service would report nothing at all - the message would just vanish
        // (with only the audit copy as evidence).
        val client = api("caller")
        client.freezeAndConnect()
        val target = "nonexistent-${System.nanoTime()}"

        try {
            val thrown = assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.rpc<WorkService>(RabbitTarget.ServiceTarget(target)).doWork("x")
            }

            assertTrue(thrown.target == target)
        } finally {
            client.disconnect()
        }
    }
}
