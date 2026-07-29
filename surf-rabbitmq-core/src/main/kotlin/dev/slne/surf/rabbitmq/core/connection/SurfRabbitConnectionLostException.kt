package dev.slne.surf.rabbitmq.core.connection

import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestException
import java.io.Serial

/** Thrown to fail pending requests when the connection to the broker is lost mid-flight. */
class SurfRabbitConnectionLostException(
    connectionName: String,
    cause: Throwable? = null
) : SurfRabbitRequestException(
    "RabbitMQ connection '$connectionName' was lost while waiting for a response",
    cause
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 4792814117769176767L
    }
}
