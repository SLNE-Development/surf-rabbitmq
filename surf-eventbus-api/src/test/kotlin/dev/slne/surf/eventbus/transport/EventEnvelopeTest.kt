package dev.slne.surf.eventbus.transport

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an envelope survives a round trip`() {
        val original = EventEnvelope(
            topic = "faction.disbanded",
            type = "dev.example.FactionDisbandedEvent",
            originInstanceId = "lobby-3",
            publishedAtEpochMs = 1_764_000_000_000,
            payload = """{"factionId":"a"}"""
        )

        val restored = EventEnvelope.decodeFromString(json, original.encodeToString(json))

        assertEquals(original, restored)
    }

    @Test
    fun `an envelope without payload survives a round trip`() {
        val original = EventEnvelope(
            topic = "player.joined",
            type = "dev.example.PlayerJoined",
            originInstanceId = "lobby-3",
            publishedAtEpochMs = 1_764_000_000_000,
            payload = null
        )

        assertEquals(original, EventEnvelope.decodeFromString(json, original.encodeToString(json)))
    }

    @Test
    fun `an unknown field in the envelope is ignored`() {
        val text = """{"topic":"a.b","type":"T","originInstanceId":"i","publishedAtEpochMs":1,"payload":null,"future":"x"}"""

        val restored = EventEnvelope.decodeFromString(json, text)

        assertEquals("a.b", restored.topic)
    }

    @Test
    fun `a malformed envelope fails with its text in the message`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventEnvelope.decodeFromString(json, "not json")
        }

        assertEquals(true, failure.message!!.contains("not json"))
    }
}
