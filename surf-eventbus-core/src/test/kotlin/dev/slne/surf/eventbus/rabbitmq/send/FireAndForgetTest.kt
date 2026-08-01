package dev.slne.surf.eventbus.rabbitmq.send

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import dev.slne.surf.eventbus.rabbitmq.topology.RabbitTopology
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RpcService
interface WorkService {
    @FireAndForget
    suspend fun doWork(text: String)
}

@RequiresDocker
class FireAndForgetTest {

    private val dataPath = Files.createTempDirectory("fnf-test")

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 3)).build()

    @Test
    fun `a handler that never returns a value is acked, not timed out`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf")
        val handled = AtomicInteger()

        val server = api(service).also {
            it.registerService<WorkService>(object : WorkService {
                override suspend fun doWork(text: String) {
                    handled.incrementAndGet()
                }
            })
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            client.rpc<WorkService>(RabbitTarget.ServiceTarget(service)).doWork("x")

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
    fun `the caller never waits, even when the handler is slow`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf-slow")
        val handled = AtomicInteger()

        val server = api(service).also {
            it.registerService<WorkService>(object : WorkService {
                override suspend fun doWork(text: String) {
                    delay(2_000)
                    handled.incrementAndGet()
                }
            })
            it.freezeAndConnect()
        }
        val client = api("caller-slow").also { it.freezeAndConnect() }

        try {
            val elapsedMillis = System.currentTimeMillis()
            client.rpc<WorkService>(RabbitTarget.ServiceTarget(service)).doWork("x")
            val callDurationMillis = System.currentTimeMillis() - elapsedMillis

            assertTrue(
                callDurationMillis < 1_000,
                "doWork() must return long before the handler's 2s delay finishes"
            )

            awaitCondition("the handler eventually runs") { handled.get() == 1 }
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
