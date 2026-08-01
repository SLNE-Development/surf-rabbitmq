package dev.slne.surf.eventbus.rabbitmq.exception

import dev.slne.surf.eventbus.rabbitmq.packet.RabbitRequestPacket
import kotlin.time.Duration

open class SurfRabbitRequestException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitRequestTimeoutException(request: RabbitRequestPacket<*>?, timeout: Duration) :
    SurfRabbitRequestException("Request $request timed out after $timeout waiting for a response")

class SurfRabbitRequestAlreadyRespondedException :
    SurfRabbitRequestException("respond() has already been called for this request")