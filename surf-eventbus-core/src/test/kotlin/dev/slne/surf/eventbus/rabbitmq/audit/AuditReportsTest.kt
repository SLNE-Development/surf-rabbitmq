package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The properties that must hold across all four report paths.
 *
 * Each path used to build its own [dev.slne.surf.eventbus.audit.AuditReport], so each could get
 * the shared identity fields subtly wrong on its own — and a wrong `messageUuid` turns one
 * message that failed four times into four unrelated incidents.
 */
class AuditReportsTest {

    private fun reports(maxPayloadBytes: Int = 1024) =
        AuditReports("surf-factions", "lobby-3", maxPayloadBytes) { 1L }

    @Test
    fun `a payload over the limit is truncated and keeps its real size`() {
        val properties = AMQP.BasicProperties.Builder().build()

        val report = reports(maxPayloadBytes = 4)
            .undeserializable(properties, ByteArray(10), "q", RuntimeException("x"))

        assertEquals(4, report.payload?.size)
        assertEquals(10, report.payloadSizeBytes, "the real size is the interesting one")
        assertTrue(report.payloadTruncated)
    }

    @Test
    fun `a payload within the limit is kept whole`() {
        val properties = AMQP.BasicProperties.Builder().build()

        val report = reports().undeserializable(properties, ByteArray(10), "q", RuntimeException("x"))

        assertEquals(10, report.payload?.size)
        assertEquals(false, report.payloadTruncated)
    }

    @Test
    fun `every path reports under the same message identity`() {
        val properties = AuditMessageIdentity.stamp(AMQP.BasicProperties.Builder().build(), "id-1")
        val reports = reports()

        assertEquals("id-1", reports.unroutable(properties, null, "x", "y", null).messageUuid)
        assertEquals(
            "id-1",
            reports.undeserializable(properties, null, "q", RuntimeException("x")).messageUuid
        )
        assertEquals(
            "id-1",
            reports.handlerFailed(
                properties, null, "x", "y", "q", "H", 1, true, null, RuntimeException("x")
            ).messageUuid
        )
    }

    @Test
    fun `the reporter is always this process`() {
        val properties = AMQP.BasicProperties.Builder().appId("some-other-service").build()

        val report = reports().undeserializable(properties, null, "q", RuntimeException("x"))

        assertEquals("some-other-service", report.originService, "the sender said who it was")
        assertEquals("surf-factions", report.reportedByService)
        assertEquals("lobby-3", report.reportedByInstance)
    }

    @Test
    fun `a cause reaches the report`() {
        val properties = AMQP.BasicProperties.Builder().build()
        val cause = IllegalStateException("boom")

        val report = reports().undeserializable(properties, null, "q", cause)

        assertEquals("java.lang.IllegalStateException", report.exceptionClass)
        assertEquals("boom", report.exceptionMessage)
        assertTrue(report.stacktrace!!.contains("boom"))
    }
}
