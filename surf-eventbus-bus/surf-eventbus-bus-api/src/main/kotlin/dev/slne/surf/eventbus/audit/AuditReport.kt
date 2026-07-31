package dev.slne.surf.eventbus.audit

import kotlinx.serialization.Serializable

/**
 * One audit incident, ready to be written to a row.
 *
 * Deliberately transport-free so that the event and query dispatchers in `surf-eventbus-bus-core`
 * can report without seeing a broker type. The RabbitMQ side wraps it in a packet.
 *
 * @property messageUuid identity across every report about the same message. The retry ladder
 *   republishes up to four times, possibly in different processes; the reporter stamps this into
 *   a header on the first failure so the attempts find each other in the database.
 * @property attempt 1-based on the retry ladder, `0` on paths without one.
 * @property terminal `true` on the last attempt — the row that used to be the dead-letter queue.
 * @property payload the message body, already truncated to the configured limit.
 */
@Serializable
data class AuditReport(
    val messageUuid: String,
    val kind: AuditKind,
    val originService: String,
    val originInstance: String?,
    val reportedByService: String,
    val reportedByInstance: String,
    val failedAtEpochMs: Long,
    val exchange: String? = null,
    val routingKey: String? = null,
    val originQueue: String? = null,
    val messageType: String? = null,
    val contract: String? = null,
    val callable: String? = null,
    val correlationId: String? = null,
    val handler: String? = null,
    val attempt: Int = 0,
    val terminal: Boolean = true,
    val retryTier: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val stacktrace: String? = null,
    val payloadEncoding: String? = null,
    val payloadSizeBytes: Int = 0,
    val payloadTruncated: Boolean = false,
    val payload: ByteArray? = null,
    val headers: Map<String, String> = emptyMap()
) {
    // ByteArray in a data class: equals/hashCode must compare content, not identity, or two
    // reports about the same message look different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuditReport) return false
        if (messageUuid != other.messageUuid) return false
        if (attempt != other.attempt) return false
        if (!payload.contentEquals(other.payload)) return false
        return kind == other.kind
    }

    override fun hashCode(): Int {
        var result = messageUuid.hashCode()
        result = 31 * result + attempt
        result = 31 * result + kind.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}
