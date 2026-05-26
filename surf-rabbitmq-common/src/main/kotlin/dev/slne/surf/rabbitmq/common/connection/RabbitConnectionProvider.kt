package dev.slne.surf.rabbitmq.common.connection

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.RecoverableChannel
import com.rabbitmq.client.RecoverableConnection

class RabbitConnectionProvider(
    private val factory: ConnectionFactory,
    val connectionName: String
): AutoCloseable {

    private val lock = Any()

    @Volatile
    private var connection: RecoverableConnection? = null

    fun connection(): RecoverableConnection {
        val connection = this.connection
        if (connection != null && connection.isOpen) {
            return connection
        }

        synchronized(lock) {
            val connection = this.connection
            if (connection != null && connection.isOpen) {
                return connection
            }

            val created = factory.newConnection(connectionName) as? RecoverableConnection
                ?: error("Connection factory returned non-recoverable connection")
            this.connection = created

            return created
        }
    }

    fun createChannel(): Channel {
        return connection().createChannel() as RecoverableChannel // connection is recoverable so this is safe
    }

    override fun close() {
        synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
        }
    }
}