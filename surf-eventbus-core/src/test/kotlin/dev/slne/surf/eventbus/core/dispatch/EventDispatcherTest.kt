package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.transport.EventEnvelope
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.BusEventCodec
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val reports = CopyOnWriteArrayList<AuditReport>()
    private val sink = object : AuditSink {
        override suspend fun report(report: AuditReport) {
            reports += report
        }
    }

    private fun envelopeFor(event: DispatchEvent, origin: String) = EventEnvelope(
        topic = "dispatch.test",
        type = DispatchEvent::class.java.name,
        originInstanceId = origin,
        publishedAtEpochMs = 1,
        payload = json.encodeToString(DispatchEvent.serializer(), event)
    )

    private fun dispatcher(registry: EventSubscriptionRegistry) = EventDispatcher(
        registry = registry,
        instanceId = "lobby-1",
        auditSink = sink,
        json = json,
        typeResolver = EventTypeResolver()
    )

    @Test
    fun `every matching handler runs`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ExactListener)
            register(WildcardListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("a"), "lobby-2"), null)

        assertEquals(listOf("exact:a", "wildcard:a"), Recorder.seen.sorted())
    }

    @Test
    fun `a failing handler does not stop its neighbour`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ThrowingListener)
            register(ExactListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("b"), "lobby-2"), null)

        assertTrue(Recorder.seen.contains("exact:b"), "the healthy handler must still have run")
        assertEquals(1, reports.size)
        assertEquals(AuditKind.EVENT_HANDLER_FAILED, reports.single().kind)
        assertEquals("ThrowingListener#onEvent", reports.single().handler)
    }

    @Test
    fun `an event from this instance is dropped unless includeSelf is set`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ExactListener)
            register(SelfListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("c"), "lobby-1"), null)

        assertEquals(listOf("self:c"), Recorder.seen)
    }

    @Test
    fun `an unresolvable type is warned about once and audited once`() = runBlocking {
        val registry = EventSubscriptionRegistry().apply { register(ExactListener) }
        val unknown = EventEnvelope("dispatch.test", "dev.example.Gone", "lobby-2", 1, "{}")
        val dispatcher = dispatcher(registry)

        dispatcher.dispatch(unknown, null)
        dispatcher.dispatch(unknown, null)

        assertEquals(1, reports.size, "a stream of unknown types must not become a stream of rows")
        assertEquals(AuditKind.UNKNOWN_EVENT_TYPE, reports.single().kind)
    }

    @Test
    fun `an event without any subscriber is not an error`() = runBlocking {
        val registry = EventSubscriptionRegistry()

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("d"), "lobby-2"), null)

        assertEquals(0, reports.size)
    }

    @Test
    fun `a codec event is decoded from its binary frame`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply { register(CodecListener) }
        val envelope = EventEnvelope("dispatch.codec", CodecEvent::class.java.name, "lobby-2", 1, null)

        dispatcher(registry).dispatch(envelope, "hello".toByteArray())

        assertEquals(listOf("codec:hello"), Recorder.seen)
    }

    @Test
    fun `a binary event whose type declares no codec is discarded, not thrown`() = runBlocking {
        val registry = EventSubscriptionRegistry().apply { register(ExactListener) }
        val envelope = EventEnvelope("dispatch.test", DispatchEvent::class.java.name, "lobby-2", 1, null)

        dispatcher(registry).dispatch(envelope, "junk".toByteArray())

        assertEquals(0, reports.size)
    }
}

@Serializable
@BusEvent("dispatch.test")
class DispatchEvent(val value: String) : SurfBusEvent()

@BusEvent("dispatch.codec")
class CodecEvent(val text: String) : SurfBusEvent() {
    companion object : BusEventCodec<CodecEvent> {
        override fun encode(buffer: ByteBuf, value: CodecEvent) {
            buffer.writeBytes(value.text.toByteArray())
        }

        override fun decode(buffer: ByteBuf): CodecEvent {
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            return CodecEvent(String(bytes))
        }
    }
}

private object Recorder {
    val seen = CopyOnWriteArrayList<String>()
}

private object ExactListener {
    @SurfSubscribe
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "exact:${event.value}"
    }
}

private object WildcardListener {
    @SurfSubscribe(topic = "dispatch.#")
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "wildcard:${event.value}"
    }
}

private object SelfListener {
    @SurfSubscribe(includeSelf = true)
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "self:${event.value}"
    }
}

private object ThrowingListener {
    @SurfSubscribe
    fun onEvent(event: DispatchEvent): Unit = error("handler is broken")
}

private object CodecListener {
    @SurfSubscribe
    fun onEvent(event: CodecEvent) {
        Recorder.seen += "codec:${event.text}"
    }
}
