package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.circuitbreaker.CircuitBreakerRegistry
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitConnectionException
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Guards outgoing calls with a per-target circuit breaker and a short retry.
 *
 * Retrying and breaking apply only to **transport** failures — the target could not be reached.
 * An exception the service deliberately threw is passed straight through: repeating it would
 * only re-run a decision the service already made, and counting it would cut off a service
 * that is demonstrably alive.
 *
 * The breaker is per target, so an unreachable `surf-punish` never delays calls to
 * `surf-factions`.
 */
class BreakerGuardedRpc(
    private val registry: CircuitBreakerRegistry,
    private val maxAttempts: Int = 3,
    private val retryDelays: List<Duration> = listOf(250.milliseconds, 1.seconds)
) {
    companion object {
        private val log = logger()
    }

    /**
     * Runs [block] under the breaker for [target], retrying transport failures.
     *
     * @throws dev.slne.surf.eventbus.circuitbreaker.CircuitOpenException if the breaker rejected the call
     */
    suspend fun <T> call(target: RabbitTarget, block: suspend () -> T): T {
        val breaker = registry.forName(target.routingKey)

        return breaker.withBreaker {
            var lastFailure: Throwable? = null

            repeat(maxAttempts) { attempt ->
                try {
                    return@withBreaker block()
                } catch (cause: Throwable) {
                    if (cause is CancellationException) throw cause

                    // Only a transport failure is worth another attempt.
                    if (!isTransportFailure(cause)) throw cause

                    lastFailure = cause

                    if (attempt < maxAttempts - 1) {
                        val backoff = retryDelays.getOrElse(attempt) { retryDelays.last() }

                        log.atFine().log(
                            "Retrying call to %s after %s (attempt %s of %s)",
                            target.routingKey, backoff, attempt + 1, maxAttempts
                        )

                        delay(backoff)
                    }
                }
            }

            throw lastFailure ?: IllegalStateException("retry loop ended without a failure")
        }
    }

    /**
     * Whether [cause] means the target was unreachable.
     *
     * A closed list, not a hierarchy match, and deliberately narrow: anything not
     * recognised is treated as a business failure and left alone, because wrongly retrying
     * a state-changing call is worse than not retrying a transport error.
     *
     * `SurfRabbitRequestTimeoutException` is deliberately **absent** — and it would slip in
     * through a naive `is SurfRabbitRequestException`, which it extends. A timeout is
     * ambiguous: the service may just be slow, and the handler may already have executed.
     * Retrying it re-runs non-idempotent work and multiplies the caller's wait; counting it
     * would open the breaker against a service that is merely busy.
     */
    private fun isTransportFailure(cause: Throwable): Boolean =
        cause is SurfRabbitServiceUnavailableException ||
                cause is SurfRabbitConnectionException
}
