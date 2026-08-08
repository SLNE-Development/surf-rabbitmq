package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport

/**
 * The four Rabbit loss paths, each turned into an [AuditReport] at one place.
 *
 * Every call site filled in the same six identity fields — message id, origin service and
 * instance, reporter service and instance, timestamp — and read the same three off the same
 * `BasicProperties`. Each could get one of them subtly wrong on its own, and a wrong
 * `messageUuid` shows up as four unrelated incidents instead of one message that failed four
 * times.
 */
class AuditReports(
    private val serviceName: String,
    private val instanceId: String,
    private val maxPayloadBytes: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param messageUuid the identity every report about this message shares.
     *
     *   Passed in rather than resolved here. [base] used to call
     *   [AuditMessageIdentity.of] itself, so on the first failure - the only time the header is
     *   absent, and therefore *every* message's first failure - the caller stamped one fresh
     *   UUID onto the retry while the report carried a different one. The grouping the whole
     *   mechanism exists for failed on hop one.
     */
    fun handlerFailed(
        messageUuid: String,
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        originQueue: String?,
        handler: String?,
        attempt: Int,
        terminal: Boolean,
        retryTier: String?,
        throwable: Throwable?,
    ): AuditReport =
        base(messageUuid, AuditKind.HANDLER_FAILED, properties, body, exchange, routingKey, originQueue)
            .copy(handler = handler, attempt = attempt, terminal = terminal, retryTier = retryTier)
            .withCause(throwable)

    fun unroutable(
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        replyText: String?,
    ): AuditReport = base(
        AuditMessageIdentity.of(properties),
        AuditKind.UNROUTABLE, properties, body, exchange, routingKey, originQueue = null
    ).copy(exceptionMessage = replyText)

    fun undeserializable(
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        originQueue: String?,
        throwable: Throwable,
    ): AuditReport = base(
        AuditMessageIdentity.of(properties),
        AuditKind.UNDESERIALIZABLE, properties, body, null, null, originQueue
    ).withCause(throwable)

    fun chunkSeriesExpired(
        messageUuid: String,
        originQueue: String?,
        receivedChunks: Int,
        expectedChunks: Int,
    ): AuditReport = AuditReport(
        messageUuid = messageUuid,
        kind = AuditKind.CHUNK_SERIES_EXPIRED,
        originService = serviceName,
        originInstance = instanceId,
        reportedByService = serviceName,
        reportedByInstance = instanceId,
        failedAtEpochMs = clock(),
        originQueue = originQueue,
        correlationId = messageUuid,
        exceptionMessage = "chunk series expired with $receivedChunks of $expectedChunks chunks",
    )

    private fun base(
        messageUuid: String,
        kind: AuditKind,
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        originQueue: String?,
    ): AuditReport {
        // Truncating here rather than at the writer means the limit also applies to a report
        // that a differently-configured process sends.
        val truncated = body != null && body.size > maxPayloadBytes

        return AuditReport(
            messageUuid = messageUuid,
            kind = kind,
            originService = properties.appId ?: serviceName,
            originInstance = properties.headers?.get("x-surf-instance")?.toString(),
            reportedByService = serviceName,
            reportedByInstance = instanceId,
            failedAtEpochMs = clock(),
            exchange = exchange,
            routingKey = routingKey,
            originQueue = originQueue,
            messageType = properties.type,
            correlationId = properties.correlationId,
            payloadEncoding = properties.contentEncoding,
            payloadSizeBytes = body?.size ?: 0,
            payloadTruncated = truncated,
            payload = if (truncated) body!!.copyOf(maxPayloadBytes) else body,
            headers = properties.headers.orEmpty()
                .mapValues { (_, value) -> value?.toString().orEmpty() },
        )
    }

    private fun AuditReport.withCause(throwable: Throwable?) = if (throwable == null) this else copy(
        exceptionClass = throwable.javaClass.name,
        exceptionMessage = throwable.message,
        stacktrace = throwable.stackTraceToString(),
    )

    companion object {
        /**
         * How much of a failed message body a report carries.
         *
         * Enough to identify the message, far short of anything that would make the audit
         * queue the largest thing on the broker.
         */
        const val DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
