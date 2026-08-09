package dev.slne.surf.eventbus.event

import dev.slne.surf.eventbus.InternalEventBusApi
import io.netty.buffer.ByteBuf

/**
 * Opt-in binary encoding for one event type, declared by its companion object.
 *
 * Events with a codec travel on `surf.eventbus.bin.<topic>` instead of the JSON channel. The
 * mechanism comes from surf-redis 1.10.1, where it exists with a JMH benchmark behind it; what
 * changed is that the topic from [BusEvent] replaces the former stable `eventId`.
 *
 * Envelope metadata — origin and timestamp — is carried by the envelope and must not be encoded
 * here.
 *
 * ```kotlin
 * @BusEvent("player.joined")
 * class PlayerJoined(val playerName: String) : SurfBusEvent() {
 *     companion object : BusEventCodec<PlayerJoined> {
 *         override fun encode(buffer: ByteBuf, value: PlayerJoined) = buffer.writeString(value.playerName)
 *         override fun decode(buffer: ByteBuf) = PlayerJoined(buffer.readString())
 *     }
 * }
 * ```
 *
 * Internal, reluctantly, and the only entry on this list that a consumer has a real reason to
 * implement. It is spelled in `io.netty.buffer.ByteBuf`, and the shadow jar relocates `io.netty`
 * — so the type in the published signature is one a consumer compiling against the artifact
 * cannot name, and a Netty upgrade would be a breaking change for everyone who had. Opting in
 * says "I accept that this moves with Netty". The way out is a buffer type of our own; until
 * then, [dev.slne.surf.eventbus.serialization] and the `KSerializer` overloads are the
 * supported path, and this is the fast one.
 */
@InternalEventBusApi
interface BusEventCodec<T : SurfBusEvent> {
    fun encode(buffer: ByteBuf, value: T)
    fun decode(buffer: ByteBuf): T
}
