package dev.slne.surf.circuitbreaker

/**
 * The state of a [CircuitBreaker].
 *
 * A breaker starts [CLOSED]. Consecutive failures move it to [OPEN], where calls are
 * rejected without being attempted. After the configured open duration it becomes
 * [HALF_OPEN] and admits a single probe call to test whether the dependency recovered.
 */
enum class CircuitState {
    /** Calls pass through. Failures are counted. */
    CLOSED,

    /** Calls are rejected immediately with [CircuitOpenException]. */
    OPEN,

    /** A single probe call is admitted; all others are rejected. */
    HALF_OPEN
}
