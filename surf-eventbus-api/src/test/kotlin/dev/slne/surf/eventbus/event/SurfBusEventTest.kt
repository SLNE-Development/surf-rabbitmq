package dev.slne.surf.eventbus.event

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfBusEventTest {

    @Test
    fun `a fresh event has no origin and no publish timestamp`() {
        val event = TestEvent("x")

        assertNull(event.originInstanceId, "origin is stamped by the bus, not by the constructor")
        assertEquals(0L, event.publishedAtEpochMs, "the timestamp is stamped at publish")
    }

    @Test
    fun `the topic comes from the annotation`() {
        assertEquals("test.plain", EventTopics.topicOf(TestEvent::class.java))
    }
}

@BusEvent("test.plain")
private class TestEvent(val value: String) : SurfBusEvent()
