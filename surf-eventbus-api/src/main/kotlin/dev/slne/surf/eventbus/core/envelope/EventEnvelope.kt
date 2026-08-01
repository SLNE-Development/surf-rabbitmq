package dev.slne.surf.eventbus.core.envelope

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What travels on the wire around an event.
 *
 * Logically identical on both channel families; the payload is JSON on
 * `surf.eventbus.json.<topic>` and `null` on `surf.eventbus.bin.<topic>`, where the event body is
 * the binary frame itself.
 */
@Serializable
data class EventEnvelope(
    val topic: String,
    val type: String,
    val originInstanceId: String,
    val publishedAtEpochMs: Long,
    val payload: String?
) {
    fun encodeToString(json: Json): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * @throws IllegalArgumentException with the offending text included. A malformed envelope
         *   is a wire-level defect and the text is the only clue the receiver has.
         */
        fun decodeFromString(json: Json, text: String): EventEnvelope = try {
            json.decodeFromString(serializer(), text)
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("not a valid event envelope: $text", exception)
        }
    }
}
