package dev.slne.surf.rabbitmq.core.retry

/**
 * One rung of the retry ladder.
 *
 * The tiers are shared by the entire fleet rather than created per service. That works
 * because a retried message is published into the tier's fanout exchange with **the name of
 * the queue it came from as routing key**: the fanout ignores the key on the way in, the
 * tier queue dead-letters to the default exchange on expiry, and the default exchange routes
 * by the preserved key — straight back into the origin queue, whichever service or shared
 * event queue that was.
 *
 * TTLs are configuration (`CommonRabbitMQConfig.getRetryTtlMillis()`), index-aligned with
 * [entries], so tests can run the full ladder in seconds. The names keep their production
 * labels either way.
 */
enum class RetryTier(val queueName: String) {
    TEN_SECONDS("surf.retry.10s"),
    ONE_MINUTE("surf.retry.60s"),
    FIVE_MINUTES("surf.retry.300s")
}

/** What to do with a message whose handler failed. */
sealed interface RetryDecision {
    /** Park the message in [tier] and let its TTL return it to the service queue. */
    data class Retry(val tier: RetryTier) : RetryDecision

    /** Give up and move the message to the service's dead-letter queue. */
    data object DeadLetter : RetryDecision
}

/**
 * Decides how many times a message has been tried and what happens next.
 *
 * Attempt counting reads RabbitMQ's own `x-death` header rather than a custom one, so the
 * count survives even when a message travels through queues this library did not publish to.
 */
object RetryPolicy {

    /** Retries after the first delivery. Four deliveries in total. */
    const val MAX_RETRIES = 3

    /**
     * How often this message has already been dead-lettered.
     *
     * A malformed header yields `0`. Retrying once too often is recoverable; throwing while
     * handling a failure is not.
     */
    fun attemptsFrom(headers: Map<String, Any?>?): Int {
        val deaths = headers?.get("x-death") as? List<*> ?: return 0

        return deaths.sumOf { entry ->
            val map = entry as? Map<*, *> ?: return@sumOf 0L
            (map["count"] as? Number)?.toLong() ?: 0L
        }.toInt()
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
