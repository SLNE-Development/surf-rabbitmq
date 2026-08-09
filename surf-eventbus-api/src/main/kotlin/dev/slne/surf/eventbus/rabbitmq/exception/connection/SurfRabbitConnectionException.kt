package dev.slne.surf.eventbus.rabbitmq.exception.connection

import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitException
import java.io.Serial

open class SurfRabbitConnectionException(
    message: String,
    cause: Throwable? = null,
) : SurfRabbitException(message, cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 4184391801304272833L
    }
}
