package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.transport.EventEnvelope
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/**
 * A binary event on the wire: envelope length, envelope JSON, payload.
 *
 * The envelope stays JSON even on the binary channel. It is small, it changes rarely, and a
 * receiver must be able to read the type before it can pick a codec to decode the body with.
 */
object BinaryFrame {

    fun encode(envelope: EventEnvelope, payload: ByteArray, json: Json): ByteArray {
        val header = envelope.encodeToString(json).toByteArray()
        return ByteBuffer.allocate(4 + header.size + payload.size)
            .putInt(header.size)
            .put(header)
            .put(payload)
            .array()
    }

    fun decode(frame: ByteArray, json: Json): Pair<EventEnvelope, ByteArray> {
        require(frame.size >= 4) { "binary frame shorter than its length prefix" }

        val buffer = ByteBuffer.wrap(frame)
        val headerSize = buffer.int
        require(headerSize in 0..(frame.size - 4)) { "binary frame declares a header of $headerSize bytes" }

        val header = ByteArray(headerSize).also(buffer::get)
        val payload = ByteArray(buffer.remaining()).also(buffer::get)

        return EventEnvelope.decodeFromString(json, String(header)) to payload
    }
}
