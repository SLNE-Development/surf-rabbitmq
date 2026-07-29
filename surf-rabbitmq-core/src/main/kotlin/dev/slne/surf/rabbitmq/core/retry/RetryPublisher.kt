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
     * @param properties the original delivery properties; their headers carry `x-death`
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
                for (piece in bodiesFor(body, rechunkAsRequest)) {
                    client.publish(
                        exchange = decision.tier.queueName,
                        routingKey = originQueue,
                        body = piece,
                        properties = withoutExpiration(properties),
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
     * Every piece carries the same (x-death bearing) properties, so the attempt count
     * stays consistent across chunks, and a fresh series id keeps the assembler from
     * mixing this attempt with a previous one (Task 10).
     */
    private fun bodiesFor(body: ByteArray, rechunkAsRequest: Boolean): ObjectList<ByteArray> =
        if (rechunkAsRequest && RabbitPacketChunking.shouldChunk(body, enabled = true)) {
            RabbitPacketChunking.splitRequest(body)
        } else {
            ObjectList.of(body)
        }

    /**
     * Copies the properties, dropping `expiration`.
     *
     * A retried RPC request would otherwise expire inside the retry tier before its TTL
     * moved it back, and disappear without reaching the dead-letter queue.
     */
    private fun withoutExpiration(properties: AMQP.BasicProperties): AMQP.BasicProperties =
        properties.builder()
            .expiration(null)
            .build()
}
