package dev.slne.surf.rabbitmq.api.event

import dev.slne.surf.rabbitmq.api.packet.RabbitPacket
import kotlinx.serialization.Serializable

/**
 * Base type for events published to the `surf.events` topic exchange.
 *
 * Unlike a request, an event has no reply and no known recipient. The publisher does not know
 * whether anyone is listening, which is exactly what keeps services decoupled: adding or
 * removing a subscriber never touches the publisher.
 *
 * Subclasses must be `@Serializable` and annotated with [RabbitEvent].
 *
 * ```kotlin
 * @Serializable
 * @RabbitEvent("faction.disbanded")
 * class FactionDisbandedEvent(val factionId: String) : RabbitEventPacket()
 * ```
 */
@Serializable
abstract class RabbitEventPacket : RabbitPacket()
