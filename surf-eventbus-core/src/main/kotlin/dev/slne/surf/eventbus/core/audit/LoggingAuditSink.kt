package dev.slne.surf.eventbus.core.audit

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink

/**
 * The fallback sink for a process without the RabbitMQ transport.
 *
 * The console stays the trace when the database cannot be reached, so a process that can never
 * reach the audit service still leaves the full incident somewhere.
 */
object LoggingAuditSink : AuditSink {

    private val log = logger()

    override suspend fun report(report: AuditReport) {
        log.atWarning().log(
            "Audit (%s) for %s on %s: handler=%s type=%s attempt=%s terminal=%s cause=%s: %s",
            report.kind, report.originService, report.originInstance, report.handler,
            report.messageType, report.attempt, report.terminal, report.exceptionClass,
            report.exceptionMessage
        )
    }
}
