package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus

object RabbitPacketPropertiesInjector {
    fun inject(packet: RabbitRequestPacket<*>, scope: CoroutineScope, requestJob: Job) {
        packet.coroutineContext = (scope + requestJob).coroutineContext
    }
}