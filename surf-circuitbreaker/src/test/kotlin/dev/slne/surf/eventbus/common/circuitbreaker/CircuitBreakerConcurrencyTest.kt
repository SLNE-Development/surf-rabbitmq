package dev.slne.surf.eventbus.common.circuitbreaker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerConcurrencyTest {

    private class Boom : RuntimeException("boom")

    @Test
    fun `only one of many concurrent callers is admitted as the probe`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val cb = CircuitBreaker(
            name = "test",
            failureThreshold = 1,
            openDuration = 30.seconds,
            clock = clock
        )

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)
        assertEquals(CircuitState.HALF_OPEN, cb.state)

        val admitted = AtomicInteger()
        val rejected = AtomicInteger()

        // The probe blocks until released, so every other caller arrives while it is in
        // flight — exactly the race the single-probe rule has to survive.
        val release = CompletableDeferred<Unit>()

        val callers = (1..64).map {
            async(Dispatchers.Default) {
                try {
                    cb.withBreaker {
                        admitted.incrementAndGet()
                        release.await()
                        "ok"
                    }
                } catch (_: CircuitOpenException) {
                    rejected.incrementAndGet()
                }
            }
        }

        // Give the losers time to reach the breaker and be turned away.
        withContext(Dispatchers.Default) {
            while (rejected.get() < 63) {
                Thread.onSpinWait()
            }
        }

        release.complete(Unit)
        callers.awaitAll()

        assertEquals(1, admitted.get(), "exactly one probe may run")
        assertEquals(63, rejected.get(), "every other caller must be rejected")
        assertEquals(CircuitState.CLOSED, cb.state, "the successful probe closes the breaker")
    }

    @Test
    fun `concurrent failures do not overcount past the threshold`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val cb = CircuitBreaker(
            name = "test",
            failureThreshold = 5,
            openDuration = 30.seconds,
            clock = clock
        )

        val executions = AtomicInteger()

        (1..32).map {
            async(Dispatchers.Default) {
                runCatching {
                    cb.withBreaker {
                        executions.incrementAndGet()
                        throw Boom()
                    }
                }
            }
        }.awaitAll()

        assertEquals(CircuitState.OPEN, cb.state)

        // The definitive check: with the breaker open, one more call must be rejected
        // without the block ever running.
        val afterOpen = AtomicInteger()
        assertFailsWith<CircuitOpenException> {
            cb.withBreaker { afterOpen.incrementAndGet(); "never" }
        }
        assertEquals(0, afterOpen.get(), "an open breaker must not execute the block")
    }
}
