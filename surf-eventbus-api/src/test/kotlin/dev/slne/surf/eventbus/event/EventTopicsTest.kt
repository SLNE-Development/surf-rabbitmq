package dev.slne.surf.eventbus.event

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventTopicsTest {

    @Test
    fun `a star matches exactly one segment`() {
        assertTrue(EventTopics.matches("faction.*", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*", "faction.member.joined"))
        assertFalse(EventTopics.matches("faction.*", "faction"))
    }

    @Test
    fun `a hash matches zero or more segments`() {
        assertTrue(EventTopics.matches("faction.#", "faction"))
        assertTrue(EventTopics.matches("faction.#", "faction.disbanded"))
        assertTrue(EventTopics.matches("faction.#", "faction.member.joined"))
        assertFalse(EventTopics.matches("faction.#", "guild.disbanded"))
    }

    @Test
    fun `a publish topic must not contain a wildcard`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.*") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.#") }
        EventTopics.validatePublishTopic("faction.disbanded")
    }

    @Test
    fun `an empty segment is rejected in topics and patterns`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction..disbanded") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validateBindingPattern("faction..#") }
    }

    @Test
    fun `topicOf reads the annotation of the event type`() {
        assertTrue(EventTopics.topicOf(AnnotatedTestEvent::class.java) == "test.annotated")
    }

    @Test
    fun `topicOf rejects an event type without an annotation`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.topicOf(UnannotatedTestEvent::class.java) }
    }
}

@BusEvent("test.annotated")
private class AnnotatedTestEvent : SurfBusEvent()

private class UnannotatedTestEvent : SurfBusEvent()
