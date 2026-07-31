package dev.slne.surf.eventbus.rabbitmq.common.packet

import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.version.RabbitMqVersion
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
