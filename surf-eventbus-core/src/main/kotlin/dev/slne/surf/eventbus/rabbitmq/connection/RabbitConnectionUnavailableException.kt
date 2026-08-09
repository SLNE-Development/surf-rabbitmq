package dev.slne.surf.eventbus.rabbitmq.connection

import com.rabbitmq.client.*
import java.io.Serial

open class RabbitConnectionUnavailableException(
    connectionName: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(
    "RabbitMQ connection '$connectionName' is unavailable: $message",
    cause
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = -5653758684314094973L
    }
}
