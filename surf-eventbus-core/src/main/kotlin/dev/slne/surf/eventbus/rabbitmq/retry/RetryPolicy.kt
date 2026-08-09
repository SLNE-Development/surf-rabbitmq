package dev.slne.surf.eventbus.rabbitmq.retry

/**
 * Decides how many times a message has been tried and what happens next.
 *
 * Attempt counting reads a header [RetryPolicy.ATTEMPTS_HEADER] that [dev.slne.surf.eventbus.rabbitmq.retry.RetryPublisher]
 * stamps itself, rather than RabbitMQ's own `x-death`. `x-death` only accumulates across a
 * dead-letter chain the broker drives entirely on its own (queue -> DLX -> queue -> DLX -> ...
 * with no client in between); the moment application code consumes a message and republishes
 * it - which retrying inherently requires, since only the application knows whether to retry
 * or dead-letter - RabbitMQ treats that republish as a brand-new message and rebuilds `x-death`
 * from scratch on the next expiry. Verified empirically: a message bounced through two queues
 * purely by broker-driven DLX chaining carries two `x-death` entries; the same message
 * round-tripped through one manual republish carries exactly one, no matter how many cycles
 * follow. A self-maintained counter has no such dependency on who republished last.
 */
object RetryPolicy {

    /** Retries after the first delivery. Four deliveries in total. */
    const val MAX_RETRIES = 3

    /** Header [RetryPublisher] stamps with the number of attempts made so far. */
    const val ATTEMPTS_HEADER = "x-surf-retry-attempts"

    /**
     * How often this message has already been retried, per [ATTEMPTS_HEADER].
     *
     * A missing or malformed header yields `0`. Retrying once too often is recoverable;
     * throwing while handling a failure is not.
     */
    fun attemptsFrom(headers: Map<String, Any?>?): Int {
        val raw = headers?.get(ATTEMPTS_HEADER) ?: return 0
        return (raw as? Number)?.toInt() ?: 0
    }

    /**
     * Where a message goes after [attempts] failures.
     *
     * @param retryEnabled `false` for handlers that are not idempotent, which must not see the
     *   same message twice
     */
    fun decide(attempts: Int, retryEnabled: Boolean): RetryDecision {
        if (!retryEnabled) return RetryDecision.DeadLetter

        return when (attempts) {
            0 -> RetryDecision.Retry(RetryTier.TEN_SECONDS)
            1 -> RetryDecision.Retry(RetryTier.ONE_MINUTE)
            2 -> RetryDecision.Retry(RetryTier.FIVE_MINUTES)
            else -> RetryDecision.DeadLetter
        }
    }
}
