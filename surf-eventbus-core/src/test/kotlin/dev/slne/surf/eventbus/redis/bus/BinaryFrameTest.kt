package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryFrameTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val envelope = EventEnvelope("a.b", "dev.example.T", "lobby-1", 7, null)

    @Test
    fun `a frame survives a round trip`() {
        val payload = byteArrayOf(1, 2, 3)

        val (restoredEnvelope, restoredPayload) = BinaryFrame.decode(
            BinaryFrame.encode(envelope, payload, json), json
        )

        assertEquals(envelope, restoredEnvelope)
        assertContentEquals(payload, restoredPayload)
    }

    @Test
    fun `an empty payload survives a round trip`() {
        val (_, restored) = BinaryFrame.decode(BinaryFrame.encode(envelope, ByteArray(0), json), json)

        assertEquals(0, restored.size)
    }

    @Test
    fun `a truncated frame fails instead of reading past its end`() {
        assertFailsWith<IllegalArgumentException> { BinaryFrame.decode(byteArrayOf(1, 2), json) }
    }

    @Test
    fun `a frame with an impossible header size fails`() {
        val bogus = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 1, 2)

        assertFailsWith<IllegalArgumentException> { BinaryFrame.decode(bogus, json) }
    }
}
