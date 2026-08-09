package dev.slne.surf.eventbus.rabbitmq.exception.connection

import java.io.Serial

class SurfRabbitPublishException : SurfRabbitConnectionException {
    constructor(
        attempts: Int,
        cause: Throwable? = null,
    ) : this("Could not publish RabbitMQ message after $attempts attempt(s)", cause)

    constructor(message: String, cause: Throwable? = null) : super(message, cause)

    companion object {
        @Serial
        private const val serialVersionUID: Long = 7041251822992156683L
    }
}
