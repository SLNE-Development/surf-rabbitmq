package dev.slne.surf.rabbitmq.common.connection

import com.rabbitmq.client.*
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
            connection?.let { return it }

            state.value = RabbitConnectionSnapshot(
                status = RabbitConnectionStatus.CONNECTING,
                generation = state.value.generation
            )

            val created = try {
                factory.newConnection(connectionName) as? RecoverableConnection
                    ?: error("Connection factory returned a non-recoverable connection")
            } catch (cause: Throwable) {
                state.value = RabbitConnectionSnapshot(
                    status = RabbitConnectionStatus.UNAVAILABLE,
                    generation = state.value.generation
                )
                throw cause
            }

            installListeners(created)
            connection = created

            state.value = RabbitConnectionSnapshot(
                status = RabbitConnectionStatus.OPEN,
                generation = state.value.generation + 1
            )

            return created
        }
    }

    suspend fun awaitOpen(expectedGeneration: Long? = null): Long {
        connection()

        val snapshot = state.first {
            it.status == RabbitConnectionStatus.OPEN || it.status == RabbitConnectionStatus.CLOSED
        }

        check(snapshot.status != RabbitConnectionStatus.CLOSED) {
            "RabbitMQ connection provider '$connectionName' is closed"
        }

        if (
            expectedGeneration != null &&
            snapshot.generation != expectedGeneration
        ) {
            throw RabbitConnectionGenerationChangedException(
                connectionName = connectionName,
                expectedGeneration = expectedGeneration,
                actualGeneration = snapshot.generation
            )
        }

        return snapshot.generation
    }

    fun createChannel(expectedGeneration: Long? = null): Channel {
        val snapshot = state.value

        check(snapshot.status == RabbitConnectionStatus.OPEN) {
            "RabbitMQ connection '$connectionName' is not open"
        }

        if (
            expectedGeneration != null &&
            snapshot.generation != expectedGeneration
        ) {
            throw RabbitConnectionGenerationChangedException(
                connectionName = connectionName,
                expectedGeneration = expectedGeneration,
                actualGeneration = snapshot.generation
            )
        }

        return connection().createChannel() as RecoverableChannel // connection is recoverable so this is safe
    }

    fun requireGeneration(expectedGeneration: Long?) {
        if (expectedGeneration == null) {
            return
        }

        val actualGeneration = state.value.generation

        if (actualGeneration != expectedGeneration) {
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

            state.value = RabbitConnectionSnapshot(
                status = RabbitConnectionStatus.UNAVAILABLE,
                generation = state.value.generation
            )

            listeners.forEach {
                it.onConnectionLost(cause)
            }
        }

        created.addRecoveryListener(
            object : RecoveryListener {
                override fun handleRecoveryStarted(recoverable: Recoverable) {
                    state.value = RabbitConnectionSnapshot(
                        status = RabbitConnectionStatus.RECOVERING,
                        generation = state.value.generation
                    )

                    listeners.forEach {
                        it.onRecoveryStarted()
                    }
                }

                override fun handleRecovery(recoverable: Recoverable) {
                    val snapshot = RabbitConnectionSnapshot(
                        status = RabbitConnectionStatus.OPEN,
                        generation = state.value.generation + 1
                    )

                    state.value = snapshot

                    listeners.forEach {
                        it.onRecoveryCompleted(snapshot.generation)
                    }
                }
            }
        )

        (created as? AutorecoveringConnection)
            ?.addQueueRecoveryListener { oldName, newName ->
                listeners.forEach {
                    it.onQueueRecovered(oldName, newName)
                }
            }
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

            runCatching {
                connection?.close()
            }

            connection = null
            listeners.clear()
        }
    }
}

class RabbitConnectionGenerationChangedException(
    connectionName: String,
    expectedGeneration: Long,
    actualGeneration: Long
) : IllegalStateException(
    "RabbitMQ connection '$connectionName' changed generation " +
            "from $expectedGeneration to $actualGeneration"
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = -8546463514822471092L
    }
}