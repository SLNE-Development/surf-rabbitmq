package dev.slne.surf.eventbus.rabbitmq.exception.connection

import java.io.Serial

class SurfRabbitConnectionClosedException(
    operation: String,
) : SurfRabbitConnectionException("Cannot perform '$operation' on a closed connection") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 6490057796999219402L
    }
}
