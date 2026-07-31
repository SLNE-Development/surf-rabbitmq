package dev.slne.surf.eventbus.rabbitmq.common.topology

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import dev.slne.surf.eventbus.rabbitmq.core.retry.RetryTier

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
     * Declares `surf.rpc`.
     *
     * Safe to call from every process and on every reconnect: redeclaring with identical
     * properties is a no-op on the broker.
     *
     * Deliberately has **no** alternate exchange. Unroutable messages must come back to the
     * publisher via `basic.return` (`mandatory = true`) so the caller can fail fast, or so the
     * failure reaches the audit (Plan 4); an AE would swallow the return either way.
     */
    fun declareExchanges() {
        channel.exchangeDeclare(
            RabbitTopology.RPC_EXCHANGE,
            BuiltinExchangeType.DIRECT,
            /* durable = */ true
        )
    }

    /**
     * Declares the shared service queue.
     *
     * Call this only on a process that hosts [serviceName].
     *
     * @return the queue name
     */
    fun declareServiceQueue(serviceName: String): String {
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
