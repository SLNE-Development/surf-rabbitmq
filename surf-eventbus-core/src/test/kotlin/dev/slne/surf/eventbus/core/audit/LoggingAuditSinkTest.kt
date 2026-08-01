package dev.slne.surf.eventbus.core.audit

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingAuditSinkTest {

    @Test
    fun `reporting never throws even when every optional field is absent`() = runBlocking {
        val report = AuditReport(
            messageUuid = "3f1b0c8e-0000-0000-0000-000000000001",
            kind = AuditKind.EVENT_HANDLER_FAILED,
            originService = "surf-factions",
            originInstance = "lobby-3",
            reportedByService = "surf-factions",
            reportedByInstance = "lobby-3",
            failedAtEpochMs = 1_764_000_000_000
        )

        LoggingAuditSink.report(report)
    }
}
