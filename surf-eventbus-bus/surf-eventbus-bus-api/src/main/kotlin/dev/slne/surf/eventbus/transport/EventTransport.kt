package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.core.envelope.EventEnvelope

/**
 * The seam between the bus and the Redis event channels.
 *
 * Exactly one real implementation exists. It is an interface so that registry and dispatcher can
 * be tested without a server — not a promise that other transports can be plugged in.
 */
interface EventTransport {

    /**
     * Subscribes and starts delivering.
     *
     * @param exactTopics wildcard-free topics; the broker filters those.
     * @param wildcardPatterns patterns that need local matching.
     * @param onEvent called per received message. The second parameter carries the binary frame
     *   of a `BusEventCodec` event and is `null` for a JSON event.
     */
    suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    )

    suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?)

    suspend fun disconnect()
}
