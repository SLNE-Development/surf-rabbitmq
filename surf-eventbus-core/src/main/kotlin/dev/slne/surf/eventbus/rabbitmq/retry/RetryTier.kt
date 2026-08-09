package dev.slne.surf.eventbus.rabbitmq.retry

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
 * TTLs are configuration (`RabbitMQSettings.retryTtlMillis`), index-aligned with
 * [entries], so tests can run the full ladder in seconds. The names keep their production
 * labels either way.
 */
enum class RetryTier(val queueName: String) {
    TEN_SECONDS("surf.retry.10s"),
    ONE_MINUTE("surf.retry.60s"),
    FIVE_MINUTES("surf.retry.300s")
}
