package dev.slne.surf.eventbus.rabbitmq.retry

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RpcService
interface FailingService {
    @FireAndForget
    suspend fun doWork(text: String)
}

@RequiresDocker
class RetryIntegrationTest {

    private val dataPath = Files.createTempDirectory("retry-integration")

    private class AlwaysFailing : FailingService {
        val attempts = AtomicInteger()

        override suspend fun doWork(text: String) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    @Test
    fun `a retryable handler is attempted again after the first delay`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("retry")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<FailingService>(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.rpc<FailingService>(RabbitTarget.ServiceTarget(service)).doWork("x")

            awaitCondition("first attempt") { handler.attempts.get() >= 1 }

            // testConfig shrinks the first tier to 500 ms.
            awaitCondition("second attempt after the first tier", timeoutMillis = 10_000) {
                handler.attempts.get() >= 2
            }

            assertTrue(
                handler.attempts.get() >= 2,
                "the message must be redelivered after the retry TTL expires - if it is " +
                        "not, check that the republish targets the tier exchange with the " +
                        "origin queue as routing key"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a failing handler climbs the full ladder and then gives up`() = runBlocking {
        // Spec test 6, end to end: first delivery plus three retries, then the audit has the
        // terminal record (Plan 4) - there is no DLQ left to land in.
        // Feasible only because testConfig shrinks the tiers to 500ms/1s/1.5s.
        val service = RabbitBrokerExtension.uniqueServiceName("full-ladder")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<FailingService>(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.rpc<FailingService>(RabbitTarget.ServiceTarget(service)).doWork("doomed")

            awaitCondition("four deliveries in total", timeoutMillis = 20_000) {
                handler.attempts.get() == 4
            }

            // Give a runaway ladder time to disprove itself.
            delay(3_000)

            assertEquals(
                4, handler.attempts.get(),
                "exactly four deliveries: the first plus three retries - more means the " +
                        "attempt counting from x-death is broken"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
