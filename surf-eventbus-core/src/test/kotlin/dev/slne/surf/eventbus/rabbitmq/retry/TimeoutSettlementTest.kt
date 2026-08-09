package dev.slne.surf.eventbus.rabbitmq.retry

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RpcService
interface HangingService {
    suspend fun neverResponds(text: String): String
}

/**
 * A timed-out handler is settled once, not twice (H4).
 *
 * The timeout path is the one that races. When `withTimeout` fires, the branch settles the
 * delivery *and* cancels `requestJob`, which completes `handlerJob` — and the completion handler
 * installed on it is the second party with an opinion about the same message. Both used to
 * settle: `RabbitAck` is idempotent so the ack was harmless, but `RetryPublisher.handleFailure`
 * is not, and running it twice writes two audit reports and parks two copies on the retry tier.
 * The origin queue then redelivers both, and the attempt ladder doubles at every rung.
 *
 * [RetryIntegrationTest] covers the ladder for a handler that *throws*, which reaches only one of
 * those paths. This covers the handler that hangs, which reaches both.
 */
@RequiresDocker
class TimeoutSettlementTest {

    private val dataPath = Files.createTempDirectory("timeout-settlement")

    private class Hanging : HangingService {
        val attempts = AtomicInteger()

        override suspend fun neverResponds(text: String): String {
            attempts.incrementAndGet()
            delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }

    @Test
    fun `a timed-out handler republishes exactly once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("hanging")
        val handler = Hanging()

        // One second, so the handler times out well inside the first retry tier's 500 ms
        // ladder rather than racing it.
        val config = testConfig(requestTimeoutSeconds = 1)

        val server = SurfRabbitApi.builder(service, dataPath).config(config).build()
        server.registerService<HangingService>(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(config).build()
        client.freezeAndConnect()

        try {
            // The call never returns; the caller's own timeout is not what is under test.
            launch {
                runCatching {
                    client.rpc<HangingService>(RabbitTarget.ServiceTarget(service))
                        .neverResponds("x")
                }
            }

            awaitCondition("the first delivery reaches the handler") {
                handler.attempts.get() >= 1
            }

            // First delivery plus three retries. A double settle makes each failure park two
            // copies, so this passes 4 almost immediately and keeps climbing.
            awaitCondition("the ladder completes", timeoutMillis = 30_000) {
                handler.attempts.get() >= 4
            }

            // Let a doubling ladder disprove itself: the tiers are 500ms/1s/1.5s, so anything
            // still in flight has landed well inside this.
            delay(5_000)

            assertEquals(
                4, handler.attempts.get(),
                "exactly four deliveries: the first plus three retries. More means the timeout " +
                        "branch and handlerJob's completion handler both settled the same " +
                        "delivery, parking two copies on the tier"
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
