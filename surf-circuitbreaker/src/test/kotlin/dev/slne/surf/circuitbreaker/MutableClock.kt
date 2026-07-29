package dev.slne.surf.circuitbreaker

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Clock] that only moves when [advance] is called.
 *
 * Lets breaker timeout behaviour be tested without real waiting, which keeps the
 * suite fast and removes timing flakiness.
 */
class MutableClock(
    instant: Instant,
    private val zone: ZoneId
) : Clock() {
    private val current = AtomicReference(instant)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current.get(), zone)

    override fun instant(): Instant = current.get()

    /** Moves the clock forward by [duration]. */
    fun advance(duration: kotlin.time.Duration) {
        current.updateAndGet { it.plusMillis(duration.inWholeMilliseconds) }
    }
}
