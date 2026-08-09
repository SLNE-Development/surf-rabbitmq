package dev.slne.surf.eventbus.rabbitmq.exception.connection

import java.io.Serial

class SurfRabbitConnectionFailedException(
    host: String,
    port: Int,
    cause: Throwable? = null,
) : SurfRabbitConnectionException("Failed to connect to RabbitMQ broker at $host:$port", cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = -1817632840115670733L
    }
}
