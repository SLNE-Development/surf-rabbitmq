package dev.slne.surf.eventbus.rabbitmq.connection

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import dev.slne.surf.eventbus.rabbitmq.rpc.EchoRpcService
import dev.slne.surf.eventbus.rabbitmq.send.WorkService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.slne.surf.eventbus.rabbitmq.exception.connection.SurfRabbitServiceUnavailableException

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
