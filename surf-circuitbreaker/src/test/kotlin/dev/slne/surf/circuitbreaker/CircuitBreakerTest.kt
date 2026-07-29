package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerTest {

    private fun breaker(
        threshold: Int = 3,
        clock: MutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        isFailure: (Throwable) -> Boolean = { true }
    ) = clock to CircuitBreaker(
        name = "test",
        failureThreshold = threshold,
        openDuration = 30.seconds,
        clock = clock,
        isFailure = isFailure
    )

    private class Boom : RuntimeException("boom")
    private class Business : RuntimeException("business rule violated")

    @Test
    fun `closed breaker returns the block result`() = runTest {
        val (_, cb) = breaker()

        assertEquals("ok", cb.withBreaker { "ok" })
        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `failures below the threshold keep the breaker closed`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        repeat(2) {
            assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `a success resets the failure counter`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        cb.withBreaker { "ok" }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `reaching the threshold opens the breaker`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        repeat(3) {
            assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        }

        assertEquals(CircuitState.OPEN, cb.state)
    }

    @Test
    fun `an open breaker rejects without running the block`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        var executions = 0

        assertFailsWith<Boom> { cb.withBreaker { executions++; throw Boom() } }
        assertFailsWith<CircuitOpenException> { cb.withBreaker { executions++; "never" } }

        assertEquals(1, executions, "the block must not run while the breaker is open")
    }

    @Test
    fun `the rejection names the breaker`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        val thrown = assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }
        assertEquals("test", thrown.breakerName)
    }

    @Test
    fun `the breaker stays open for the full open duration`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        clock.advance(29.seconds)

        assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }
        assertEquals(CircuitState.OPEN, cb.state)
    }

    @Test
    fun `after the open duration a probe is admitted`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        clock.advance(30.seconds)

        assertEquals("probe", cb.withBreaker { "probe" })
    }

    @Test
    fun `a successful probe closes the breaker`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)

        cb.withBreaker { "probe" }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `a failed probe reopens the breaker and restarts the timer`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertEquals(CircuitState.OPEN, cb.state)

        clock.advance(29.seconds)
        assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }

        clock.advance(1.seconds)
        assertEquals("probe", cb.withBreaker { "probe" })
    }

    @Test
    fun `throwables rejected by the predicate do not count`() = runTest {
        val (_, cb) = breaker(threshold = 2, isFailure = { it !is Business })

        repeat(10) {
            assertFailsWith<Business> { cb.withBreaker { throw Business() } }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `business failures do not reset counted failures`() = runTest {
        val (_, cb) = breaker(threshold = 2, isFailure = { it !is Business })

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Business> { cb.withBreaker { throw Business() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        assertEquals(
            CircuitState.OPEN, cb.state,
            "an ignored throwable must not clear the counter the way a success does"
        )
    }

    @Test
    fun `cancellation never counts as a failure`() = runTest {
        val (_, cb) = breaker(threshold = 1)

        repeat(5) {
            assertFailsWith<CancellationException> {
                cb.withBreaker { throw CancellationException("cancelled") }
            }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `reset returns an open breaker to closed`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertEquals(CircuitState.OPEN, cb.state)

        cb.reset()

        assertEquals(CircuitState.CLOSED, cb.state)
        assertEquals("ok", cb.withBreaker { "ok" })
    }

    @Test
    fun `the original exception propagates unchanged`() = runTest {
        val (_, cb) = breaker(threshold = 5)
        val thrown = assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertTrue(thrown.message == "boom")
    }
}
