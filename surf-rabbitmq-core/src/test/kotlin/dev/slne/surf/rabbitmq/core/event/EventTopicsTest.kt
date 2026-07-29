package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
@RabbitEvent("faction.disbanded")
class FactionDisbandedTestEvent(val id: String) : RabbitEventPacket()

@Serializable
class UnannotatedTestEvent : RabbitEventPacket()

class EventTopicsTest {

    @Test
    fun `the topic comes from the annotation`() {
        assertEquals("faction.disbanded", EventTopics.topicOf(FactionDisbandedTestEvent::class.java))
    }

    @Test
    fun `an unannotated event is rejected with a helpful message`() {
        val thrown = assertFailsWith<IllegalStateException> {
            EventTopics.topicOf(UnannotatedTestEvent::class.java)
        }

        assertTrue(
            thrown.message!!.contains("@RabbitEvent"),
            "the message must name the missing annotation, but was: ${thrown.message}"
        )
    }

    @Test
    fun `publish topics may not contain wildcards`() {
        // A wildcard in a publish key is not an error on the broker - it is simply
        // treated as a literal and matches nothing, which is far harder to debug.
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.*") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.#") }
    }

    @Test
    fun `publish topics reject empty segments`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction..x") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic(".faction") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.") }
    }

    @Test
    fun `a blank publish topic is rejected`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("   ") }
    }

    @Test
    fun `valid publish topics are accepted`() {
        EventTopics.validatePublishTopic("faction.disbanded")
        EventTopics.validatePublishTopic("player.punish.ban")
        EventTopics.validatePublishTopic("single")
    }

    @Test
    fun `binding patterns may contain wildcards`() {
        EventTopics.validateBindingPattern("faction.*")
        EventTopics.validateBindingPattern("faction.#")
        EventTopics.validateBindingPattern("#")
        EventTopics.validateBindingPattern("faction.*.disbanded")
    }

    @Test
    fun `star matches exactly one segment`() {
        assertTrue(EventTopics.matches("faction.*", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*", "faction.a.b"))
        assertFalse(EventTopics.matches("faction.*", "faction"))
    }

    @Test
    fun `hash matches zero or more segments`() {
        assertTrue(EventTopics.matches("faction.#", "faction.a.b"))
        assertTrue(EventTopics.matches("faction.#", "faction.a"))
        assertTrue(EventTopics.matches("faction.#", "faction"))
        assertTrue(EventTopics.matches("#", "anything.at.all"))
    }

    @Test
    fun `a wildcard in the middle matches one segment`() {
        assertTrue(EventTopics.matches("faction.*.disbanded", "faction.abc.disbanded"))
        assertFalse(EventTopics.matches("faction.*.disbanded", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*.disbanded", "faction.a.b.disbanded"))
    }

    @Test
    fun `an exact pattern matches only itself`() {
        assertTrue(EventTopics.matches("faction.disbanded", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.disbanded", "faction.created"))
    }
}
