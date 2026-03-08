package dev.slne.surf.rabbitmq.api.exception

abstract class SurfRabbitException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
