package dev.slne.surf.eventbus.rabbitmq.exception.connection

import dev.slne.surf.eventbus.rabbitmq.packet.RabbitRequestPacket
import kotlin.time.Duration

class SurfRabbitRequestTimeoutException(
    request: RabbitRequestPacket<*>?,
    timeout: Duration,
) : SurfRabbitRequestException("Request $request timed out after $timeout waiting for a response")
