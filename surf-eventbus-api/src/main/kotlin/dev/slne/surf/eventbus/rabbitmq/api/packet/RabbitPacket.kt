package dev.slne.surf.eventbus.rabbitmq.api.packet

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.version.RabbitMqVersion
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
     * Only meaningful on received packets. [RabbitMqVersion.UNKNOWN] if the sender
     * runs a library version older than `1.6.0` or did not announce a version.
     */
    @Transient
    var senderVersion: RabbitMqVersion = RabbitMqVersion.UNKNOWN
        @InternalRabbitMQ set
}