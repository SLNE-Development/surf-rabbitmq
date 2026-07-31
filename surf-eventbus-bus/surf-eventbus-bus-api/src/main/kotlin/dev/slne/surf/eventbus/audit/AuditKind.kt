package dev.slne.surf.eventbus.audit

/**
 * Why a message is being audited.
 *
 * Every value marks a path on which a message would otherwise vanish without a trace. Failures
 * the caller learns about — a rejected publish, an RPC timeout, an expired request — are
 * deliberately absent: they are errors with an addressee, not losses.
 */
enum class AuditKind {
    HANDLER_FAILED,
    UNROUTABLE,
    UNDESERIALIZABLE,
    CHUNK_SERIES_EXPIRED,
    UNKNOWN_EVENT_TYPE,
    EVENT_HANDLER_FAILED,
    QUERY_HANDLER_FAILED
}
