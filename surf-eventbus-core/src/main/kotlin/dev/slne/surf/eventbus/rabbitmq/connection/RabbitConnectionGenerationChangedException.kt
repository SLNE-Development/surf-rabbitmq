package dev.slne.surf.eventbus.rabbitmq.connection

import com.rabbitmq.client.*
import java.io.Serial

class RabbitConnectionGenerationChangedException(
    connectionName: String,
    expectedGeneration: Long,
    actualGeneration: Long
) : RabbitConnectionUnavailableException(
    connectionName = connectionName,
    message = "Connection generation changed from $expectedGeneration to $actualGeneration"
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = -8546463514822471092L
    }
}
