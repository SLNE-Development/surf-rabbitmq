package dev.slne.surf.rabbitmq.common.connection

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.RecoverableChannel
import com.rabbitmq.client.RecoverableConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionClosedException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionFailedException
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal

class RabbitConnectionProvider(
    private val factory: ConnectionFactory,
    val connectionName: String
): AutoCloseable {

    companion object {
        private const val CLOSE_TIMEOUT_MILLIS = 10_000
    }

    init {
        require(connectionName.isNotBlank()) { "RabbitMQ connection name must not be blank" }
    }

    private val lock = Any()

    @Volatile
    private var connection: RecoverableConnection? = null

    @Volatile
    private var closed = false

    fun connection(): RecoverableConnection {
        if (closed) throw SurfRabbitConnectionClosedException("open connection")
        connection?.let { return it }

        synchronized(lock) {
            if (closed) throw SurfRabbitConnectionClosedException("open connection")
            connection?.let { return it }

            val rawConnection = try {
                factory.newConnection(connectionName)
            } catch (throwable: Throwable) {
                throwable.rethrowIfFatal()
                throw SurfRabbitConnectionFailedException(factory.host, factory.port, throwable)
            }
            val created = rawConnection as? RecoverableConnection ?: run {
                val failure = SurfRabbitConnectionException(
                    "Connection factory returned a non-recoverable connection"
                )
                try {
                    rawConnection.close(CLOSE_TIMEOUT_MILLIS)
                } catch (cleanupFailure: Throwable) {
                    cleanupFailure.rethrowIfFatal()
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
            this.connection = created

            return created
        }
    }

    fun createChannel(): Channel {
        val rawChannel = connection().createChannel()
            ?: throw SurfRabbitConnectionException(
                "RabbitMQ broker refused to create a channel; the negotiated channel limit may be exhausted"
            )
        return rawChannel as? RecoverableChannel ?: run {
            val failure = SurfRabbitConnectionException(
                "Recoverable connection returned a non-recoverable channel"
            )
            try {
                rawChannel.close()
            } catch (cleanupFailure: Throwable) {
                cleanupFailure.rethrowIfFatal()
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val currentConnection = connection
            connection = null
            currentConnection?.close(CLOSE_TIMEOUT_MILLIS)
        }
    }
}
