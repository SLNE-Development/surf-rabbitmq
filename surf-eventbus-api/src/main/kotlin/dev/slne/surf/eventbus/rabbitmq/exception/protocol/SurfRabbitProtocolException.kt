package dev.slne.surf.eventbus.rabbitmq.exception.protocol

import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitException

open class SurfRabbitProtocolException(
    message: String,
    cause: Throwable? = null,
) : SurfRabbitException(message, cause)
