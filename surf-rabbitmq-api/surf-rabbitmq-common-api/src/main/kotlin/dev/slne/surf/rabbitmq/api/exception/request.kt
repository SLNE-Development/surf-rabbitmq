package dev.slne.surf.rabbitmq.api.exception

import kotlin.time.Duration

open class SurfRabbitRequestException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitRequestTimeoutException(timeout: Duration) :
    SurfRabbitRequestException("Request timed out after $timeout waiting for a response")

class SurfRabbitRequestAlreadyRespondedException :
    SurfRabbitRequestException("respond() has already been called for this request")