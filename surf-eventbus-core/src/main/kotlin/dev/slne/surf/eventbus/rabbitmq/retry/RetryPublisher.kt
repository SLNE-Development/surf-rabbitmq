package dev.slne.surf.eventbus.rabbitmq.retry

import com.rabbitmq.client.AMQP
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.rabbitmq.audit.AuditMessageIdentity
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitClient
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitPacketChunking
import it.unimi.dsi.fastutil.objects.ObjectList
import dev.slne.surf.eventbus.rabbitmq.audit.AuditReports

/**
 * Moves a message whose handler failed onto the retry ladder, reporting every attempt to the
 * audit.
 *
 * Republishing rather than `basicNack(requeue = true)` is deliberate: requeueing returns the
 * message to the head of its own queue for immediate redelivery, which spins at full CPU and
 * blocks every message behind it. Parking the message in a TTL tier delays the retry instead.
 */
class RetryPublisher(
    private val client: RabbitClient,
    private val auditSink: AuditSink,
    private val instanceId: String
) {

    companion object {
        private val log = logger()
    }

    /**
     * Republishes the failed message and returns what was decided.
     *
     * The caller must `ack` the original delivery afterwards: the message now exists in
     * another queue (or, on the last attempt, only in the audit), and leaving the original
     * unacked would duplicate it.
     *
     * @param body the **assembled** body. For a chunked request the raw delivery body is
     *   only the final chunk — the earlier ones were acked individually — so republishing
     *   a delivery body would park an orphan chunk that can never assemble again.
     * @param properties the original delivery properties; their headers carry
     *   [RetryPolicy.ATTEMPTS_HEADER] and [AuditMessageIdentity.HEADER]
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
        rechunkAsRequest: Boolean,
        exception: Throwable? = null
    ): RetryDecision {
        val attempts = RetryPolicy.attemptsFrom(properties.headers)
        val decision = RetryPolicy.decide(attempts, retryEnabled)
        val messageId = AuditMessageIdentity.of(properties)
        val reports = AuditReports(serviceName, instanceId, AuditReports.DEFAULT_MAX_PAYLOAD_BYTES)

        auditSink.report(
            reports.handlerFailed(
                messageUuid = messageId,
                properties = properties,
                body = body,
                exchange = null,
                routingKey = null,
                originQueue = originQueue,
                handler = null,
                attempt = attempts + 1,
                terminal = decision is RetryDecision.DeadLetter,
                retryTier = (decision as? RetryDecision.Retry)?.tier?.queueName,
                throwable = exception,
            )
        )

        when (decision) {
            is RetryDecision.Retry -> {
                log.atInfo().log(
                    "Retrying message from %s in %s (attempt %s of %s)",
                    originQueue, decision.tier.queueName, attempts + 1, RetryPolicy.MAX_RETRIES
                )

                // Into the tier's fanout exchange with the origin queue as routing key:
                // insertion ignores the key, expiry routes by it via the default exchange.
                val nextAttemptProperties = withNextAttempt(properties, attempts + 1, messageId)
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
                    "Giving up on message from %s after %s attempts (retry enabled: %s); the audit has the record",
                    originQueue, attempts, retryEnabled
                )
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
     * with [attempts] and [AuditMessageIdentity.HEADER] with [messageId].
     *
     * Expiration is dropped because a retried RPC request would otherwise expire inside the
     * retry tier before its TTL moved it back, and disappear without a trace. The attempt count
     * is self-maintained rather than read back from `x-death`: the republish below is a fresh
     * `basic.publish`, and RabbitMQ rebuilds `x-death` from scratch on the next expiry of any
     * message that left broker control this way (see [RetryPolicy] for how this was verified).
     * The identity header must survive the same republish, or the next attempt's report would
     * mint a fresh id and no longer group with this one.
     *
     * The identity stamp itself is [AuditMessageIdentity.stamp]; this used to re-implement it
     * inline, which left the real function tested but unused by anything in production.
     */
    private fun withNextAttempt(
        properties: AMQP.BasicProperties,
        attempts: Int,
        messageId: String
    ): AMQP.BasicProperties {
        val headers = HashMap<String, Any?>(properties.headers ?: emptyMap())
        headers[RetryPolicy.ATTEMPTS_HEADER] = attempts

        val withAttempts = properties.builder()
            .expiration(null)
            .headers(headers)
            .build()

        return AuditMessageIdentity.stamp(withAttempts, messageId)
    }
}
