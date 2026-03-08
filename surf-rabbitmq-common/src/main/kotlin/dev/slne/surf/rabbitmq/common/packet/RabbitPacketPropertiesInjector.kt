package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import kotlinx.coroutines.CoroutineScope

object RabbitPacketPropertiesInjector {

    @JvmStatic
    fun inject(packet: RabbitRequestPacket<*>, scope: CoroutineScope) {
        packet.coroutineContext = scope.coroutineContext
    }
}