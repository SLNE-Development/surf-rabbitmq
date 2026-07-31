package dev.slne.surf.eventbus.rabbitmq.common.topology

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
     * On service queues (combined with `reject-publish`) this stops a service that has been
     * down for days from exhausting broker memory and taking every other service down with it.
     * On audit queues (combined with `drop-head`) it caps queues nobody consumes.
     */
    const val MAX_QUEUE_BYTES = 268_435_456L

    /**
     * Durable, replicated, bounded, dead-lettered.
     *
     * No `x-dead-letter-routing-key` is set on purpose: a nacked message keeps the routing
     * key it was delivered with — the service name — which is exactly what the
     * `surf.dlq.<service>` binding on `surf.dlx` matches.
     */
    fun serviceQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
        "x-max-length-bytes" to MAX_QUEUE_BYTES,
        "x-overflow" to "reject-publish"
    )

    /**
     * Durable and replicated, bounded, but **not** dead-lettered.
     *
     * Not dead-lettered: a dead-letter queue that dead-letters would cycle messages
     * endlessly. Bounded with `drop-head` rather than `reject-publish`: nothing consumes
     * this queue, and rejecting would make the dead-letter path itself fail.
     *
     * Also used for the `surf.unroutable` audit queue, which has the same lifecycle.
     */
    fun deadLetterQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-max-length-bytes" to MAX_QUEUE_BYTES,
        "x-overflow" to "drop-head"
    )

    /**
     * Same durability as a service queue, two deliberate differences:
     *
     * - `x-dead-letter-routing-key` pins dead-letters to the service name. Events carry
     *   their *topic* as routing key; without the pin a nacked event would enter the
     *   direct `surf.dlx` with a topic key, match no binding, and vanish.
     * - `drop-head` instead of `reject-publish`. Publisher confirms only ack once every
     *   bound queue accepted the message, so `reject-publish` would let one full
     *   subscriber queue fail every publisher of matching topics fleet-wide.
     */
    fun sharedEventQueue(serviceName: String): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
        "x-dead-letter-routing-key" to serviceName,
        "x-max-length-bytes" to MAX_QUEUE_BYTES,
        "x-overflow" to "drop-head"
    )

    /**
     * No arguments at all.
     *
     * Reply, instance and per-instance event queues are `exclusive` and `autoDelete`, which
     * quorum queues do not support. They die with their process, which is the intent.
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
