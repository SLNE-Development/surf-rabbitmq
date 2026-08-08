package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records what was reported instead of sending it anywhere.
 *
 * Its absence is why C5 went unnoticed: with no way to observe what the dispatchers reported,
 * nothing could show that they were reporting into a logger rather than to the audit service.
 */
class FakeAuditSink : AuditSink {

    val reports = CopyOnWriteArrayList<AuditReport>()

    override suspend fun report(report: AuditReport) {
        reports += report
    }
}
