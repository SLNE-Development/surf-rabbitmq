package dev.slne.surf.eventbus.common.circuitbreaker

import java.io.Serial

/**
 * Thrown when a call is rejected because the breaker is [CircuitState.OPEN], or because
 * another probe is already in flight while [CircuitState.HALF_OPEN].
 *
 * The guarded block was **not** executed when this is thrown.
 */
class CircuitOpenException(
    val breakerName: String
) : IllegalStateException("Circuit breaker '$breakerName' is open") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}
