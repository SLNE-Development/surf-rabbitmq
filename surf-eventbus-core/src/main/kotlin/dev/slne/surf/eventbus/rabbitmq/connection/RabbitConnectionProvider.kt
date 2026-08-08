package dev.slne.surf.eventbus.rabbitmq.connection

import com.rabbitmq.client.*
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.io.Serial
import java.util.concurrent.CopyOnWriteArrayList

class RabbitConnectionProvider(
    private val factory: ConnectionFactory,
    val connectionName: String
) : AutoCloseable {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<RabbitConnectionListener>()

    private val state = MutableStateFlow(
        RabbitConnectionSnapshot(
            status = RabbitConnectionStatus.NEW,
            generation = 0
        )
    )

    @Volatile
    private var connection: RecoverableConnection? = null

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = state.value.status == RabbitConnectionStatus.OPEN && connection?.isOpen == true

    val generation: Long
        get() = state.value.generation

    fun addListener(listener: RabbitConnectionListener) {
        listeners += listener
    }

    fun removeListener(listener: RabbitConnectionListener) {
        listeners -= listener
    }

    fun connection(): RecoverableConnection {
        check(!closed) {
            "RabbitMQ connection provider '$connectionName' is closed"
        }

        connection?.let { return it }

        synchronized(lock) {
            check(!closed) {
                "RabbitMQ connection provider '$connectionName' is closed"
            }

            connection?.let { return it }

            updateStatus(RabbitConnectionStatus.CONNECTING)

            val created = try {
                factory.newConnection(connectionName) as? RecoverableConnection
                    ?: error("Connection factory returned a non-recoverable connection")
            } catch (cause: Throwable) {
                updateStatus(RabbitConnectionStatus.UNAVAILABLE)
                throw cause
            }

            connection = created
            installListeners(created)

            if (created.isOpen) {
                markOpen()
            } else {
                updateStatus(RabbitConnectionStatus.UNAVAILABLE)
            }

            return created
        }
    }

    suspend fun awaitOpen(expectedGeneration: Long? = null): Long {
        connection()

        val snapshot = state.first { current ->
            current.status == RabbitConnectionStatus.CLOSED ||
                    (current.status == RabbitConnectionStatus.OPEN && connection?.isOpen == true)
        }

        check(snapshot.status != RabbitConnectionStatus.CLOSED) {
            "RabbitMQ connection provider '$connectionName' is closed"
        }

        requireGeneration(
            expectedGeneration = expectedGeneration,
            actualGeneration = snapshot.generation
        )

        return snapshot.generation
    }

    fun createChannel(expectedGeneration: Long): Channel {
        requireOpenGeneration(expectedGeneration)

        val currentConnection = connection
            ?: throw RabbitConnectionUnavailableException(
                connectionName,
                "No RabbitMQ connection has been created"
            )

        return try {
            val created = currentConnection.createChannel()
                ?: throw RabbitConnectionUnavailableException(
                    connectionName,
                    "RabbitMQ returned no channel"
                )

            created as? RecoverableChannel
                ?: error("Recoverable connection returned a non-recoverable channel")
        } catch (cause: Throwable) {
            val latestSnapshot = state.value

            if (
                cause is IOException ||
                cause is ShutdownSignalException ||
                latestSnapshot.status != RabbitConnectionStatus.OPEN ||
                latestSnapshot.generation != expectedGeneration ||
                !currentConnection.isOpen
            ) {
                throw RabbitConnectionUnavailableException(
                    connectionName = connectionName,
                    message = "Could not open a RabbitMQ channel",
                    cause = cause
                )
            }

            throw cause
        }
    }

    fun requireOpenGeneration(expectedGeneration: Long) {
        val snapshot = state.value
        val currentConnection = connection

        if (
            snapshot.status != RabbitConnectionStatus.OPEN ||
            currentConnection?.isOpen != true
        ) {
            throw RabbitConnectionUnavailableException(
                connectionName,
                "RabbitMQ connection is not open"
            )
        }

        requireGeneration(
            expectedGeneration = expectedGeneration,
            actualGeneration = snapshot.generation
        )
    }

    fun requireGeneration(expectedGeneration: Long?) {
        if (expectedGeneration == null) {
            return
        }

        requireGeneration(
            expectedGeneration = expectedGeneration,
            actualGeneration = state.value.generation
        )
    }

    private fun requireGeneration(
        expectedGeneration: Long?,
        actualGeneration: Long
    ) {
        if (
            expectedGeneration != null &&
            actualGeneration != expectedGeneration
        ) {
            throw RabbitConnectionGenerationChangedException(
                connectionName = connectionName,
                expectedGeneration = expectedGeneration,
                actualGeneration = actualGeneration
            )
        }
    }

    private fun installListeners(created: RecoverableConnection) {
        created.addShutdownListener { cause ->
            if (closed || cause.isInitiatedByApplication) {
                return@addShutdownListener
            }

            updateStatus(RabbitConnectionStatus.UNAVAILABLE)

            listeners.forEach { listener ->
                listener.onConnectionLost(cause)
            }
        }

        created.addRecoveryListener(
            object : RecoveryListener {
                override fun handleRecoveryStarted(recoverable: Recoverable) {
                    if (closed) {
                        return
                    }

                    updateStatus(RabbitConnectionStatus.RECOVERING)

                    listeners.forEach { listener ->
                        listener.onRecoveryStarted()
                    }
                }

                override fun handleRecovery(recoverable: Recoverable) {
                    if (closed) {
                        return
                    }

                    val snapshot = markOpen()

                    listeners.forEach { listener ->
                        listener.onRecoveryCompleted(snapshot.generation)
                    }
                }
            }
        )

        (created as? AutorecoveringConnection)
            ?.addQueueRecoveryListener { oldName, newName ->
                listeners.forEach { listener ->
                    listener.onQueueRecovered(oldName, newName)
                }
            }
    }

    private fun updateStatus(status: RabbitConnectionStatus) {
        if (closed) {
            return
        }

        val current = state.value

        if (current.status == RabbitConnectionStatus.CLOSED) {
            return
        }

        state.value = current.copy(status = status)
    }

    private fun markOpen(): RabbitConnectionSnapshot {
        val current = state.value

        val snapshot = RabbitConnectionSnapshot(
            status = RabbitConnectionStatus.OPEN,
            generation = current.generation + 1
        )

        state.value = snapshot
        return snapshot
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }

            closed = true

            state.value = RabbitConnectionSnapshot(
                status = RabbitConnectionStatus.CLOSED,
                generation = state.value.generation
            )

            val current = connection
            connection = null

            runCatching {
                current?.close()
            }

            listeners.clear()
        }
    }
}

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