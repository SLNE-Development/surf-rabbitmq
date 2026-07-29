package dev.slne.surf.rabbitmq.common.topology

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import dev.slne.surf.rabbitmq.core.retry.RetryTier

/**
 * Declares exchanges, queues and bindings on a channel.
 *
 * Exchange declaration is idempotent and every process performs it. Queue declaration is not
 * shared: **a process declares only queues it consumes itself**. Declaring another service's
 * queue was the cause of the `PRECONDITION_FAILED (406)` failures in 1.6.x, where a client
 * declared the server's queue with arguments the server later disagreed with.
 *
 * All methods are blocking and must not run on a coroutine dispatcher that disallows blocking.
 */
class RabbitTopologyDeclarer(private val channel: Channel) {

    /**
     * Declares the three exchanges.
     *
     * Safe to call from every process and on every reconnect: redeclaring with identical
     * properties is a no-op on the broker.
     *
     * `surf.rpc` deliberately has **no** alternate exchange. Unroutable messages must come
     * back to the publisher via `basic.return` (`mandatory = true`) so the caller can fail
     * fast; an AE would swallow the return. The audit copy in [RabbitTopology.UNROUTABLE_QUEUE]
     * is produced by the return listener republishing (Plan 4), not by the broker.
     */
    fun declareExchanges() {
        channel.exchangeDeclare(
            RabbitTopology.RPC_EXCHANGE,
            BuiltinExchangeType.DIRECT,
            /* durable = */ true
        )

        channel.exchangeDeclare(
            RabbitTopology.EVENTS_EXCHANGE,
            BuiltinExchangeType.TOPIC,
            /* durable = */ true
        )

        channel.exchangeDeclare(
            RabbitTopology.DLX_EXCHANGE,
            BuiltinExchangeType.DIRECT,
            /* durable = */ true
        )
    }

    /**
     * Declares the dead-letter queue for [serviceName] and binds it to [RabbitTopology.DLX_EXCHANGE].
     *
     * Called from [declareServiceQueue] and (in Plan 3) from `declareSharedEventQueue`: any
     * process whose queues dead-letter under this service name must ensure the DLQ exists,
     * otherwise dead-lettered messages route into `surf.dlx`, match nothing, and vanish.
     *
     * @return the queue name
     */
    fun declareDeadLetterQueue(serviceName: String): String {
        val dlq = RabbitTopology.deadLetterQueue(serviceName)
        channel.queueDeclare(dlq, true, false, false, QueueArguments.deadLetterQueue())
        channel.queueBind(dlq, RabbitTopology.DLX_EXCHANGE, serviceName)

        return dlq
    }

    /**
     * Declares the shared service queue and its dead-letter queue, and binds both.
     *
     * Call this only on a process that hosts [serviceName].
     *
     * @return the queue name
     */
    fun declareServiceQueue(serviceName: String): String {
        declareDeadLetterQueue(serviceName)

        val queue = RabbitTopology.serviceQueue(serviceName)
        channel.queueDeclare(queue, true, false, false, QueueArguments.serviceQueue())
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, serviceName)

        return queue
    }

    /**
     * Declares this process's private queue for directly addressed messages.
     *
     * Exclusive and auto-deleting: it disappears when the process goes away, so an offline
     * instance leaves nothing behind on the broker.
     */
    fun declareInstanceQueue(instanceId: String): String {
        val queue = RabbitTopology.instanceQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, instanceId)

        return queue
    }

    /**
     * Declares this process's RPC reply queue.
     *
     * Bound to nothing: replies are addressed through the default exchange using the queue
     * name as routing key, which is what the `replyTo` property carries.
     */
    fun declareReplyQueue(instanceId: String): String {
        val queue = RabbitTopology.replyQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())

        return queue
    }

    /**
     * Declares the audit queue for returned (unroutable) messages.
     *
     * Bound to nothing: the publish-side return listener republishes returned messages into
     * it by name through the default exchange (Plan 4). Declared by every process at connect,
     * with identical arguments, so it exists before the first return can happen.
     */
    fun declareUnroutableQueue(): String {
        val queue = RabbitTopology.UNROUTABLE_QUEUE
        channel.queueDeclare(queue, true, false, false, QueueArguments.deadLetterQueue())

        return queue
    }

    /**
     * Declares the durable queue shared by all instances of [serviceName] and binds it to
     * every pattern in [patterns].
     *
     * Because all instances consume this one queue, exactly one of them handles each event.
     *
     * Also declares the service's dead-letter queue: this queue dead-letters (with the
     * routing key pinned to [serviceName]), and an event-only subscriber never calls
     * [declareServiceQueue] — without the DLQ its dead-letters would enter `surf.dlx`,
     * match no binding, and vanish.
     *
     * @return the queue name
     */
    fun declareSharedEventQueue(serviceName: String, patterns: Set<String>): String {
        declareDeadLetterQueue(serviceName)

        val queue = RabbitTopology.sharedEventQueue(serviceName)
        channel.queueDeclare(queue, true, false, false, QueueArguments.sharedEventQueue(serviceName))

        for (pattern in patterns) {
            channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, pattern)
        }

        return queue
    }

    /**
     * Declares this process's private event queue and binds it to every pattern in [patterns].
     *
     * Each instance owns one, so every instance receives its own copy of a matching event.
     * Exclusive and auto-deleting: events sent while the process is down are not retained.
     *
     * @return the queue name
     */
    fun declareInstanceEventQueue(instanceId: String, patterns: Set<String>): String {
        val queue = RabbitTopology.instanceEventQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())

        for (pattern in patterns) {
            channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, pattern)
        }

        return queue
    }

    /**
     * Declares the three shared retry tiers: a fanout exchange and a queue per tier,
     * bound together.
     *
     * The fanout exchange exists because the republish must carry the origin queue's
     * name as routing key *without* that key affecting insertion. Publishing into the
     * tier queue via the default exchange instead would stamp the tier queue's own name
     * as routing key — and expiry would then route the message back into the tier queue
     * itself, looping it forever.
     *
     * Nothing ever consumes these queues; messages leave by TTL expiry only.
     *
     * @param ttlMillis per-tier TTLs, index-aligned with [RetryTier.entries]; from
     *   `CommonRabbitMQConfig.getRetryTtlMillis()`, so every process on a broker agrees
     */
    fun declareRetryTiers(ttlMillis: List<Long>) {
        require(ttlMillis.size == RetryTier.entries.size) {
            "expected one TTL per retry tier"
        }

        RetryTier.entries.forEachIndexed { index, tier ->
            channel.exchangeDeclare(tier.queueName, BuiltinExchangeType.FANOUT, true)
            channel.queueDeclare(
                tier.queueName,
                /* durable = */ true,
                /* exclusive = */ false,
                /* autoDelete = */ false,
                QueueArguments.retryQueue(ttlMillis[index])
            )
            channel.queueBind(tier.queueName, tier.queueName, "")
        }
    }
}
