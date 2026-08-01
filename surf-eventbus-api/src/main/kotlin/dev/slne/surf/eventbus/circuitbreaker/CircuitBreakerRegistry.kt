package dev.slne.surf.eventbus.circuitbreaker

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds one [CircuitBreaker] per named target, created on first use.
 *
 * Per-target isolation is the point: one unreachable dependency must not cause calls to a
 * healthy one to be rejected. A single shared breaker would do exactly that.
 *
 * ```kotlin
 * val registry = CircuitBreakerRegistry(isFailure = { it is IOException })
 *
 * registry.forName("surf-punish").withBreaker { call() }
 * ```
 */
class CircuitBreakerRegistry(
    private val failureThreshold: Int = 5,
    private val openDuration: Duration = 30.seconds,
    private val clock: Clock = Clock.systemUTC(),
    private val isFailure: (Throwable) -> Boolean = { true }
) {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    /** Returns the breaker for [name], creating it on first call. */
    fun forName(name: String): CircuitBreaker = breakers.computeIfAbsent(name) {
        CircuitBreaker(
            name = it,
            failureThreshold = failureThreshold,
            openDuration = openDuration,
            clock = clock,
            isFailure = isFailure
        )
    }

    /** The names of all breakers created so far. */
    fun names(): Set<String> = breakers.keys.toSet()

    /** Closes every breaker and clears its failure counter. */
    fun resetAll() {
        breakers.values.forEach(CircuitBreaker::reset)
    }
}
