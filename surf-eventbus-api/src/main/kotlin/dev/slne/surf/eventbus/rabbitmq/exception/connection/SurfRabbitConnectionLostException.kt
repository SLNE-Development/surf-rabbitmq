package dev.slne.surf.eventbus.rabbitmq.exception.connection

import java.io.Serial

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
    cause: Throwable? = null,
) : SurfRabbitConnectionException(
        "RabbitMQ connection '$connectionName' was lost while waiting for a response",
        cause,
    ) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 4792814117769176767L
    }
}
