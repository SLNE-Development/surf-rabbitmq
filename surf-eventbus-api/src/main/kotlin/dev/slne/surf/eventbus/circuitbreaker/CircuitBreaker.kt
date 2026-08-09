package dev.slne.surf.eventbus.circuitbreaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Stops a caller from repeatedly waiting on a dependency that is already known to be down.
 *
 * After [failureThreshold] consecutive counted failures the breaker opens and rejects calls
 * immediately with [CircuitOpenException] for [openDuration]. It then admits a single probe
 * call: if the probe succeeds the breaker closes, otherwise it opens again for another
 * [openDuration].
 *
 * Which throwables count is decided by [isFailure]. This matters when guarding a remote call:
 * a transport error means the dependency is unreachable and should trip the breaker, while a
 * business exception proves the dependency is alive and must not. [CancellationException] is
 * never counted, regardless of [isFailure] — it signals that the *caller* went away.
 *
 * Time is read through [clock] so that timeout behaviour can be tested without waiting.
 *
 * Instances are safe to use from multiple coroutines. The guarded block runs outside the
 * internal lock, so a slow call never blocks state transitions of other callers.
 *
 * ```kotlin
 * val breaker = CircuitBreaker(
 *     name = "surf-punish",
 *     isFailure = { it is IOException }
 * )
 *
 * val result = breaker.withBreaker { remoteCall() }
 * ```
 */
class CircuitBreaker(
    val name: String,
    private val failureThreshold: Int = 5,
    private val openDuration: Duration = 30.seconds,
    private val clock: Clock = Clock.systemUTC(),
    private val isFailure: (Throwable) -> Boolean = { true },
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive, was $failureThreshold" }
        require(openDuration.isPositive()) { "openDuration must be positive, was $openDuration" }
    }

    private val lock = Mutex()

    private var currentState: CircuitState = CircuitState.CLOSED
    private var consecutiveFailures: Int = 0
    private var openedAt: Instant? = null
    private var probeInFlight: Boolean = false

    /**
     * The current state.
     *
     * Reading this transitions [CircuitState.OPEN] to [CircuitState.HALF_OPEN] if
     * [openDuration] has elapsed, so the value always reflects what the next call would do.
     */
    suspend fun currentState() =
        lock.withLock {
            refreshState()
            currentState
        }

    /**
     * Runs [block] unless the breaker is currently rejecting calls.
     *
     * @throws CircuitOpenException if the call was rejected. [block] did not run.
     */
    suspend fun <T> withBreaker(block: suspend () -> T): T {
        acquirePermit()

        return try {
            val result = block()
            withContext(NonCancellable) { onSuccess() }
            result
        } catch (cause: Throwable) {
            // NonCancellable because the bookkeeping has to land even when the caller is being
            // cancelled: acquirePermit may have marked a probe in flight, and a Mutex — unlike
            // the synchronized block this replaced — refuses to lock in a cancelled coroutine.
            // Without it a cancelled probe would leave probeInFlight stuck true and the breaker
            // would reject every later call for good.
            withContext(NonCancellable) { onFailure(cause) }
            throw cause
        }
    }

    /** Forces the breaker back to [CircuitState.CLOSED] and clears the failure counter. */
    suspend fun reset() =
        lock.withLock {
            currentState = CircuitState.CLOSED
            consecutiveFailures = 0
            openedAt = null
            probeInFlight = false
        }

    private suspend fun acquirePermit() =
        lock.withLock {
            refreshState()

            when (currentState) {
                CircuitState.CLOSED -> Unit

                CircuitState.OPEN -> throw CircuitOpenException(name)

                CircuitState.HALF_OPEN -> {
                    // Exactly one probe is allowed to test the dependency. Admitting more
                    // would send a burst at a service that is likely still recovering.
                    if (probeInFlight) throw CircuitOpenException(name)
                    probeInFlight = true
                }
            }
        }

    private suspend fun onSuccess() =
        lock.withLock {
            currentState = CircuitState.CLOSED
            consecutiveFailures = 0
            openedAt = null
            probeInFlight = false
        }

    private suspend fun onFailure(cause: Throwable) =
        lock.withLock {
            // A cancelled caller says nothing about the dependency's health.
            if (cause is CancellationException || !isFailure(cause)) {
                probeInFlight = false
                return@withLock
            }

            if (currentState == CircuitState.HALF_OPEN) {
                open()
                return@withLock
            }

            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) {
                open()
            }
        }

    /**
     * Must be called while holding [lock].
     *
     * Taking the lock itself would deadlock: its only caller, [onFailure], already holds it,
     * and a [Mutex] is not reentrant the way the `synchronized` block this replaced was.
     */
    private fun open() {
        currentState = CircuitState.OPEN
        openedAt = clock.instant()
        probeInFlight = false
    }

    /** Must be called while holding [lock]. */
    private fun refreshState() {
        if (currentState != CircuitState.OPEN) return

        val since = openedAt ?: return
        val elapsed = java.time.Duration.between(since, clock.instant())

        if (elapsed.toMillis() >= openDuration.inWholeMilliseconds) {
            currentState = CircuitState.HALF_OPEN
            probeInFlight = false
        }
    }
}
