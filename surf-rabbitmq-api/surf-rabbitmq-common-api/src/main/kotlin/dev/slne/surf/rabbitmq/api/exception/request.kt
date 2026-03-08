package dev.slne.surf.rabbitmq.api.exception

open class SurfRabbitRequestException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitRequestTimeoutException(timeoutSeconds: Long) :
    SurfRabbitRequestException("Request timed out after ${timeoutSeconds}s waiting for a response")

class SurfRabbitRequestAlreadyRespondedException :
    SurfRabbitRequestException("respond() has already been called for this request")
