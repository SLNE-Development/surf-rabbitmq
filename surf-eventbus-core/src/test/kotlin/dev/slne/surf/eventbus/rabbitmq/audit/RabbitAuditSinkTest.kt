package dev.slne.surf.eventbus.rabbitmq.audit

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

private class RecordingAuditService : AuditService {
    val received = CopyOnWriteArrayList<AuditReport>()
    override suspend fun report(report: AuditReport) {
        received.add(report)
    }
}

private object ThrowingAuditService : AuditService {
    override suspend fun report(report: AuditReport) {
        throw IllegalStateException("audit unreachable")
    }
}

private class BlockingAuditService : AuditService {
    private val gate = CompletableDeferred<Unit>()
    private val calls = AtomicInteger(0)

    override suspend fun report(report: AuditReport) {
        calls.incrementAndGet()
        gate.await()
    }
}

class RabbitAuditSinkTest {

    private fun report(kind: AuditKind = AuditKind.HANDLER_FAILED) = AuditReport(
        messageUuid = "id-1",
        kind = kind,
        originService = "surf-factions",
        originInstance = "lobby-3",
        reportedByService = "surf-factions",
        reportedByInstance = "lobby-3",
        failedAtEpochMs = 1
    )

    @Test
    fun `the audit service does not report to itself`() = runBlocking {
        val proxy = RecordingAuditService()
        val sink = RabbitAuditSink(proxy, serviceName = "surf-eventbus-audit", auditServiceName = "surf-eventbus-audit")

        sink.report(report())

        assertEquals(0, proxy.received.size, "a self-report would feed itself forever")
    }

    @Test
    fun `a disabled sink sends nothing`() = runBlocking {
        val proxy = RecordingAuditService()
        val sink = RabbitAuditSink(proxy, "surf-factions", "surf-eventbus-audit", enabled = false)

        sink.report(report())

        assertEquals(0, proxy.received.size)
    }

    @Test
    fun `a failing publish does not propagate`() = runBlocking {
        val sink = RabbitAuditSink(ThrowingAuditService, "surf-factions", "surf-eventbus-audit")

        sink.report(report())
        // No exception: the original path must not fail because the audit was unreachable.
    }

    @Test
    fun `overflow drops reports and counts them`() = runBlocking {
        val proxy = BlockingAuditService()
        val sink = RabbitAuditSink(proxy, "surf-factions", "surf-eventbus-audit", queueCapacity = 4)

        repeat(100) { sink.report(report()) }

        assertEquals(true, sink.droppedCount() > 0, "an outage must not become a second outage")
    }
}
