package dev.slne.surf.eventbus.rabbitmq.core.rpc

import dev.slne.surf.eventbus.common.circuitbreaker.CircuitBreakerRegistry
import dev.slne.surf.eventbus.common.circuitbreaker.CircuitOpenException
import dev.slne.surf.eventbus.common.circuitbreaker.CircuitState
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitConnectionException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitPublishException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class BreakerGuardedRpcTest {

    private class BusinessError : RuntimeException("not found")

    private fun guarded(registry: CircuitBreakerRegistry) = BreakerGuardedRpc(
        registry = registry,
        maxAttempts = 3,
        retryDelays = listOf(1.milliseconds, 1.milliseconds)
    )

    private fun registry() = CircuitBreakerRegistry(
        failureThreshold = 2,
        isFailure = {
            it is SurfRabbitServiceUnavailableException || it is SurfRabbitConnectionException
        }
    )

    @Test
    fun `a successful call returns its result`() = runTest {
        val result = guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `a transport failure is retried`() = runTest {
        val attempts = AtomicInteger()

        val result = guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) {
            if (attempts.incrementAndGet() < 3) {
                throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
            }
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `a business exception is not retried`() = runTest {
        val attempts = AtomicInteger()

        assertFailsWith<BusinessError> {
            guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) {
                attempts.incrementAndGet()
                throw BusinessError()
            }
        }

        assertEquals(
            1, attempts.get(),
            "retrying a business error just repeats a decision the service already made"
        )
    }

    @Test
    fun `a business exception does not open the breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(10) {
            assertFailsWith<BusinessError> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) { throw BusinessError() }
            }
        }

        assertEquals(
            CircuitState.CLOSED, registry.forName("svc").state,
            "a service answering with errors is alive and must not be cut off"
        )
    }

    @Test
    fun `repeated transport failures open the breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            assertFailsWith<SurfRabbitServiceUnavailableException> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
                }
            }
        }

        assertEquals(CircuitState.OPEN, registry.forName("svc").state)
    }

    @Test
    fun `an open breaker rejects without calling`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            runCatching {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
                }
            }
        }

        val attempts = AtomicInteger()
        assertFailsWith<CircuitOpenException> {
            rpc.call(RabbitTarget.ServiceTarget("svc")) { attempts.incrementAndGet(); "never" }
        }

        assertEquals(0, attempts.get())
    }

    @Test
    fun `one dead service does not affect another`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            runCatching {
                rpc.call(RabbitTarget.ServiceTarget("dead")) {
                    throw SurfRabbitServiceUnavailableException("dead", "NO_ROUTE")
                }
            }
        }

        assertEquals(CircuitState.OPEN, registry.forName("dead").state)
        assertEquals(
            "ok", rpc.call(RabbitTarget.ServiceTarget("healthy")) { "ok" },
            "a per-service breaker must isolate failures; a global one would not"
        )
    }

    @Test
    fun `instance targets get their own breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        rpc.call(RabbitTarget.InstanceTarget("lobby-3")) { "ok" }

        assertEquals(setOf("lobby-3"), registry.names())
    }

    @Test
    fun `a timeout is neither retried nor counted`() = runTest {
        // SurfRabbitRequestTimeoutException EXTENDS SurfRabbitRequestException - this test
        // is the guard against someone "simplifying" the predicate to that supertype.
        // A timed-out request may already have executed; retrying re-runs it, and counting
        // it would open the breaker against a service that is merely slow.
        val registry = registry()
        val rpc = guarded(registry)
        val attempts = AtomicInteger()

        repeat(10) {
            assertFailsWith<SurfRabbitRequestTimeoutException> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    attempts.incrementAndGet()
                    throw SurfRabbitRequestTimeoutException(null, 1.milliseconds)
                }
            }
        }

        assertEquals(10, attempts.get(), "a timeout must not be retried")
        assertEquals(
            CircuitState.CLOSED, registry.forName("svc").state,
            "timeouts must not open the breaker"
        )
    }

    @Test
    fun `a publish failure is retried within one call, counted as a single verdict`() = runTest {
        // The clearest transport failure of all - the message never left this process.
        // It extends SurfRabbitConnectionException, NOT SurfRabbitRequestException, which
        // is why the predicate must not be a naive hierarchy match.
        //
        // The breaker wraps the whole retry loop as one pass/fail verdict per call (see
        // "a transport failure is retried": that only works if 2 failed attempts inside one
        // call do NOT alone trip a threshold of 2). So one call exhausting all 3 attempts
        // counts as exactly one failure - not open yet on its own; "repeated transport
        // failures open the breaker" covers reaching OPEN across separate calls.
        val registry = registry()
        val rpc = guarded(registry)
        val attempts = AtomicInteger()

        assertFailsWith<SurfRabbitPublishException> {
            rpc.call(RabbitTarget.ServiceTarget("svc")) {
                attempts.incrementAndGet()
                throw SurfRabbitPublishException("nacked", null)
            }
        }

        assertEquals(3, attempts.get(), "a publish failure must use all retry attempts")
        assertEquals(
            CircuitState.CLOSED, registry.forName("svc").state,
            "one call is one verdict regardless of its internal retries; threshold=2 needs a second failed call"
        )
    }
}
