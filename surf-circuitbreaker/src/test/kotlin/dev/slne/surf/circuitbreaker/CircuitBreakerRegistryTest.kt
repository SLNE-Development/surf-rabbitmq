package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerRegistryTest {

    private class Boom : RuntimeException("boom")

    private fun registry(clock: MutableClock) = CircuitBreakerRegistry(
        failureThreshold = 1,
        openDuration = 30.seconds,
        clock = clock
    )

    @Test
    fun `the same name yields the same breaker`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assertSame(reg.forName("surf-punish"), reg.forName("surf-punish"))
    }

    @Test
    fun `different names yield different breakers`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assert(reg.forName("surf-punish") !== reg.forName("surf-factions"))
    }

    @Test
    fun `one failing target does not open the breaker of another`() = runTest {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        val punish = reg.forName("surf-punish")
        val factions = reg.forName("surf-factions")

        assertFailsWith<Boom> { punish.withBreaker { throw Boom() } }

        assertEquals(CircuitState.OPEN, punish.state)
        assertEquals(CircuitState.CLOSED, factions.state)
        assertEquals("ok", factions.withBreaker { "ok" })
    }

    @Test
    fun `names lists every created breaker`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        reg.forName("a")
        reg.forName("b")
        reg.forName("a")

        assertEquals(setOf("a", "b"), reg.names())
    }

    @Test
    fun `resetAll closes every breaker`() = runTest {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        val a = reg.forName("a")
        val b = reg.forName("b")
        assertFailsWith<Boom> { a.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { b.withBreaker { throw Boom() } }

        reg.resetAll()

        assertEquals(CircuitState.CLOSED, a.state)
        assertEquals(CircuitState.CLOSED, b.state)
    }

    @Test
    fun `the breaker is named after the target`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assertEquals("surf-punish", reg.forName("surf-punish").name)
    }
}
