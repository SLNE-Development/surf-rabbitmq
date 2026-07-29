package dev.slne.surf.rabbitmq.core.retry

import com.rabbitmq.client.AMQP
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import it.unimi.dsi.fastutil.objects.ObjectList

/**
 * Moves a message whose handler failed either onto the retry ladder or into the dead-letter
 * queue.
 *
 * Republishing rather than `basicNack(requeue = true)` is deliberate: requeueing returns the
 * message to the head of its own queue for immediate redelivery, which spins at full CPU and
 * blocks every message behind it. Parking the message in a TTL tier delays the retry instead.
 */
class RetryPublisher(private val client: RabbitClient) {

    companion object {
        private val log = logger()
    }

    /**
     * Republishes the failed message and returns what was decided.
     *
     * The caller must `ack` the original delivery afterwards: the message now exists in
     * another queue, and leaving the original unacked would duplicate it.
     *
     * @param body the **assembled** body. For a chunked request the raw delivery body is
     *   only the final chunk — the earlier ones were acked individually — so republishing
     *   a delivery body would park an orphan chunk that can never assemble again.
     * @param properties the original delivery properties; their headers carry
     *   [RetryPolicy.ATTEMPTS_HEADER]
     * @param originQueue the queue this delivery was consumed from; becomes the routing key
     *   of the republish, and therefore the destination after TTL expiry
     * @param rechunkAsRequest split oversized bodies into a fresh chunk series before
     *   republishing (request path only). Without it, a reassembled multi-chunk body can
     *   exceed the broker's max message size. Events are never chunked, so the event
     *   consumer passes `false`.
     */
    suspend fun handleFailure(
        body: ByteArray,
        properties: AMQP.BasicProperties,
        originQueue: String,
        serviceName: String,
        retryEnabled: Boolean,
        rechunkAsRequest: Boolean
    ): RetryDecision {
        val attempts = RetryPolicy.attemptsFrom(properties.headers)
        val decision = RetryPolicy.decide(attempts, retryEnabled)

        when (decision) {
            is RetryDecision.Retry -> {
                log.atInfo().log(
                    "Retrying message from %s in %s (attempt %s of %s)",
                    originQueue, decision.tier.queueName, attempts + 1, RetryPolicy.MAX_RETRIES
                )

                // Into the tier's fanout exchange with the origin queue as routing key:
                // insertion ignores the key, expiry routes by it via the default exchange.
                val nextAttemptProperties = withNextAttempt(properties, attempts + 1)
                for (piece in bodiesFor(body, rechunkAsRequest)) {
                    client.publish(
                        exchange = decision.tier.queueName,
                        routingKey = originQueue,
                        body = piece,
                        properties = nextAttemptProperties,
                        mandatory = false
                    )
                }
            }

            RetryDecision.DeadLetter -> {
                log.atWarning().log(
                    "Dead-lettering message from %s after %s attempts (retry enabled: %s)",
                    originQueue, attempts, retryEnabled
                )

                for (piece in bodiesFor(body, rechunkAsRequest)) {
                    client.publish(
                        exchange = RabbitTopology.DLX_EXCHANGE,
                        routingKey = serviceName,
                        body = piece,
                        properties = properties,
                        mandatory = false
                    )
                }
            }
        }

        return decision
    }

    /**
     * Re-chunks a body that only fit through the broker in pieces.
     *
     * Every piece carries the same properties (including the attempt-count header), so the
     * attempt count stays consistent across chunks, and a fresh series id keeps the
     * assembler from mixing this attempt with a previous one (Task 10).
     */
    private fun bodiesFor(body: ByteArray, rechunkAsRequest: Boolean): ObjectList<ByteArray> =
        if (rechunkAsRequest && RabbitPacketChunking.shouldChunk(body, enabled = true)) {
            RabbitPacketChunking.splitRequest(body)
        } else {
            ObjectList.of(body)
        }

    /**
     * Copies the properties, dropping `expiration` and stamping [RetryPolicy.ATTEMPTS_HEADER]
     * with [attempts].
     *
     * Expiration is dropped because a retried RPC request would otherwise expire inside the
     * retry tier before its TTL moved it back, and disappear without reaching the dead-letter
     * queue. The attempt count is self-maintained rather than read back from `x-death`: the
     * republish below is a fresh `basic.publish`, and RabbitMQ rebuilds `x-death` from scratch
     * on the next expiry of any message that left broker control this way (see
     * [RetryPolicy] for how this was verified).
     */
    private fun withNextAttempt(properties: AMQP.BasicProperties, attempts: Int): AMQP.BasicProperties {
        val headers = HashMap<String, Any?>(properties.headers ?: emptyMap())
        headers[RetryPolicy.ATTEMPTS_HEADER] = attempts

        return properties.builder()
            .expiration(null)
            .headers(headers)
            .build()
    }
}
