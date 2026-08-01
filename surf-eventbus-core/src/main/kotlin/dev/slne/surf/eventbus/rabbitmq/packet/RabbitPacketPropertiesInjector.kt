package dev.slne.surf.eventbus.rabbitmq.packet

import dev.slne.surf.eventbus.rabbitmq.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.version.RabbitMqVersion
import kotlinx.coroutines.CoroutineScope

object RabbitPacketPropertiesInjector {
    fun inject(
        packet: RabbitRequestPacket<*>,
        scope: CoroutineScope,
        senderVersion: RabbitMqVersion = RabbitMqVersion.UNKNOWN
    ) {
        packet.coroutineContext = scope.coroutineContext
        packet.senderVersion = senderVersion
    }
}
