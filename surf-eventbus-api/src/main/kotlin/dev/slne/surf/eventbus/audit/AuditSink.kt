package dev.slne.surf.eventbus.audit

/**
 * Where audit reports go.
 *
 * Implemented over RabbitMQ when that transport is enabled, and by a logging fallback otherwise.
 * Implementations must never throw: the original path — acking a message, logging a handler
 * failure — must not fail because the audit was unreachable.
 */
interface AuditSink {
    suspend fun report(report: AuditReport)
}
