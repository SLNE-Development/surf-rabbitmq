package dev.slne.surf.eventbus.rabbitmq.retry

/** What to do with a message whose handler failed. */
sealed interface RetryDecision {
    /** Park the message in [tier] and let its TTL return it to the service queue. */
    data class Retry(val tier: RetryTier) : RetryDecision

    /** Give up and move the message to the service's dead-letter queue. */
    data object DeadLetter : RetryDecision
}
