package dev.slne.surf.eventbus.rabbitmq.packet

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.version.RabbitMQVersion
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.OffsetDateTime

@Serializable
abstract class RabbitPacket {
    val timestamp: @Contextual OffsetDateTime = OffsetDateTime.now()

    /**
     * The library version announced by the peer that sent this packet.
     *
     * Only meaningful on received packets. [RabbitMQVersion.UNKNOWN] if the sender
     * runs a library version older than `1.6.0` or did not announce a version.
     */
    @Transient
    var senderVersion: RabbitMQVersion = RabbitMQVersion.UNKNOWN
        @InternalEventBusApi set
}