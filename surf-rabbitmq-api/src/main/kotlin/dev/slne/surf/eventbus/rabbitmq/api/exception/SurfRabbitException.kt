package dev.slne.surf.eventbus.rabbitmq.api.exception

abstract class SurfRabbitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)