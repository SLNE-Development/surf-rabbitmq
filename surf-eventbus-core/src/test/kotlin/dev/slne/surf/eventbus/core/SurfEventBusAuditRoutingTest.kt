package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.transport.EventEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Regression test for C5.
 *
 * Both dispatchers used to be constructed with a hardcoded `LoggingAuditSink` and no injection
 * point, while the sink that actually publishes to `surf-eventbus-audit` was reachable only
 * from the RabbitMQ paths. `EVENT_HANDLER_FAILED`, `QUERY_HANDLER_FAILED` and
 * `UNKNOWN_EVENT_TYPE` were therefore logged and dropped - three of the seven audit kinds never
 * reaching the service the README says every loss reaches.
 */
class SurfEventBusAuditRoutingTest {

    private val dataPath = Files.createTempDirectory("bus-audit-routing")

    private fun busWith(sink: FakeAuditSink, transport: FakeEventTransport) = SurfEventBusImpl(
        serviceName = "surf-test",
        instanceId = "lobby-1",
        dataPath = dataPath,
        serializers = SerializersModule { },
        rabbitApi = null,
        redisApi = null,
        eventTransport = transport,
        queryTransport = null,
        auditSink = sink
    )

    @Test
    fun `a failing event handler reports to the configured sink`() = runBlocking {
        val sink = FakeAuditSink()
        val transport = FakeEventTransport()
        val bus = busWith(sink, transport)

        bus.subscribe(ThrowingListener)
        bus.freezeAndConnect()

        transport.deliver(envelope(AuditRoutingEvent::class.java.name, "audit.routing"))

        val report = sink.reports.single()
        assertEquals(AuditKind.EVENT_HANDLER_FAILED, report.kind)
        assertEquals("audit.routing", report.routingKey)
        assertEquals("java.lang.IllegalStateException", report.exceptionClass)
    }

    @Test
    fun `an unresolvable event type reports to the configured sink`() = runBlocking {
        val sink = FakeAuditSink()
        val transport = FakeEventTransport()
        val bus = busWith(sink, transport)

        bus.subscribe(ThrowingListener)
        bus.freezeAndConnect()

        transport.deliver(envelope("dev.example.NoSuchEventType", "audit.routing"))

        assertEquals(AuditKind.UNKNOWN_EVENT_TYPE, sink.reports.single().kind)
    }

    private fun envelope(type: String, topic: String) = EventEnvelope(
        topic = topic,
        type = type,
        originInstanceId = "other-instance",
        publishedAtEpochMs = System.currentTimeMillis(),
        payload = "{}"
    )
}

@Serializable
@BusEvent("audit.routing")
class AuditRoutingEvent : SurfBusEvent()

private object ThrowingListener {
    @SurfSubscribe
    fun onEvent(event: AuditRoutingEvent): Unit = error("handler blew up")
}
