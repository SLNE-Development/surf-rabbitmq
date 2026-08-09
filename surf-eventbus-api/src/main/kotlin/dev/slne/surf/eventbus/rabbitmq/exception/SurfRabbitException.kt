package dev.slne.surf.eventbus.rabbitmq.exception

import dev.slne.surf.eventbus.exception.SurfEventBusException

abstract class SurfRabbitException(message: String, cause: Throwable? = null) :
    SurfEventBusException(message, cause)
