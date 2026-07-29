package dev.slne.surf.circuitbreaker

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class MutableClockTest {
    @Test
    fun `advance moves the clock forward by the given duration`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start, ZoneOffset.UTC)

        assertEquals(start, clock.instant())

        clock.advance(30.seconds)

        assertEquals(start.plusSeconds(30), clock.instant())
    }

    @Test
    fun `clock does not move on its own`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start, ZoneOffset.UTC)

        repeat(1000) { clock.instant() }

        assertEquals(start, clock.instant())
    }
}
