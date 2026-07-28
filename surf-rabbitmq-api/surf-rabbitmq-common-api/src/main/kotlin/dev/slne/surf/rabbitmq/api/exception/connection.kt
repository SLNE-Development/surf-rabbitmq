package dev.slne.surf.rabbitmq.api.exception

import java.io.Serial

open class SurfRabbitConnectionException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 4184391801304272833L
    }
}

class SurfRabbitConnectionFailedException(host: String, port: Int, cause: Throwable? = null) :
    SurfRabbitConnectionException("Failed to connect to RabbitMQ broker at $host:$port", cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = -1817632840115670733L
    }
}

class SurfRabbitConnectionClosedException(operation: String) :
    SurfRabbitConnectionException("Cannot perform '$operation' on a closed connection") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 6490057796999219402L
    }
}

class SurfRabbitPublishException : SurfRabbitConnectionException {
    constructor(
        attempts: Int,
        cause: Throwable? = null
    ) : this("Could not publish RabbitMQ message after $attempts attempt(s)", cause)

    constructor(message: String, cause: Throwable? = null) : super(message, cause)

    companion object {
        @Serial
        private const val serialVersionUID: Long = 7041251822992156683L
    }
}