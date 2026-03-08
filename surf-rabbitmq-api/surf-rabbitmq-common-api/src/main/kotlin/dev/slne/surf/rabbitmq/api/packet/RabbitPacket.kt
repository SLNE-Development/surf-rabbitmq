package dev.slne.surf.rabbitmq.api.packet

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
abstract class RabbitPacket {
    val timestamp: @Contextual OffsetDateTime = OffsetDateTime.now()

}