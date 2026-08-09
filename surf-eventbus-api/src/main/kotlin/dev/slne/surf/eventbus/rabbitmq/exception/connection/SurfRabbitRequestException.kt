package dev.slne.surf.eventbus.rabbitmq.exception.connection

import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitException

open class SurfRabbitRequestException(
    message: String,
    cause: Throwable? = null,
) : SurfRabbitException(message, cause)
