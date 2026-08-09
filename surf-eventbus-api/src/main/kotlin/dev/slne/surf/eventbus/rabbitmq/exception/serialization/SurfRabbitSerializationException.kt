package dev.slne.surf.eventbus.rabbitmq.exception.serialization

import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitException

open class SurfRabbitSerializationException(
    message: String,
    cause: Throwable? = null,
) : SurfRabbitException(message, cause)
