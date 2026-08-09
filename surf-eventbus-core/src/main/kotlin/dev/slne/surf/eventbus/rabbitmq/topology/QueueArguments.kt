package dev.slne.surf.eventbus.rabbitmq.topology

/**
 * Argument maps for each kind of queue.
 *
 * These arguments are part of a queue's identity: redeclaring an existing queue with
 * different arguments fails the channel with `PRECONDITION_FAILED (406)`. Keeping them in one
 * place is what allows every declaring process to agree.
 */
object QueueArguments {

    /**
     * Upper bound on a bounded queue, in bytes.
     *
     * Stops a service that has been down for days from exhausting broker memory and taking
     * every other service down with it.
     */
    const val MAX_QUEUE_BYTES = 268_435_456L

    /**
     * Durable, replicated, bounded.
     *
     * No dead-letter exchange: a failed message is reported to the audit and then simply
     * acked or dropped, not parked in a second queue nobody reads.
     */
    fun serviceQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-max-length-bytes" to MAX_QUEUE_BYTES,
        "x-overflow" to "reject-publish"
    )

    /**
     * No arguments at all.
     *
     * Reply and instance queues are `exclusive` and `autoDelete`, which quorum queues do not
     * support. They die with their process, which is the intent.
     */
    fun ephemeralQueue(): Map<String, Any> = emptyMap()

    /**
     * A holding queue whose TTL expiry returns the message to its origin.
     *
     * `x-dead-letter-exchange: ""` is the default exchange, which routes by queue name.
     * The republish into the tier carries the origin queue's name as routing key, so
     * expiry delivers the message straight back into that queue — no per-service tiers,
     * no re-broadcast through a topic exchange.
     *
     * `x-dead-letter-routing-key` is deliberately **absent**: the preserved per-message
     * key IS the routing mechanism. Pinning it would send every retried message of the
     * whole fleet to one queue.
     */
    fun retryQueue(ttlMillis: Long): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-message-ttl" to ttlMillis,
        "x-dead-letter-exchange" to ""
    )
}
