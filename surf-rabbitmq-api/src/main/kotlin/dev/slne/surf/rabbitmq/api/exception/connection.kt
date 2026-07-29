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

/**
 * Thrown to fail pending requests when the connection to the broker is lost mid-flight.
 *
 * Re-parented under [SurfRabbitConnectionException] (was [SurfRabbitRequestException]):
 * losing the connection *is* a transport failure, and [BreakerGuardedRpc]'s transport
 * predicate matches on [SurfRabbitConnectionException] - as a request exception this would
 * have been invisible to it. Breaking change, explicitly permitted.
 */
class SurfRabbitConnectionLostException(
    connectionName: String,
    cause: Throwable? = null
) : SurfRabbitConnectionException(
    "RabbitMQ connection '$connectionName' was lost while waiting for a response",
    cause
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 4792814117769176767L
    }
}