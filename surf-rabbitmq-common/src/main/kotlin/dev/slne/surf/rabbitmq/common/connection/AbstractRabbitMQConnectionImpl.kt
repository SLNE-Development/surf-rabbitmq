package dev.slne.surf.rabbitmq.common.connection

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionClosedException
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitConsumer
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class AbstractRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: CommonRabbitMQConfig,
) : RabbitMQConnection {
    private enum class LifecycleState {
        NEW,
        CONNECTED,
        CLOSED
    }

    private val lifecycleMutex = Mutex()
    private var lifecycleState = LifecycleState.NEW
    private val clientLazy = lazy {
        RabbitClient.create(config, api.pluginName)
    }

    val client by clientLazy

    protected lateinit var queueName: String
        private set

    protected lateinit var mainConsumer: RabbitConsumer
        private set

    final override suspend fun connect(): Unit = lifecycleMutex.withLock {
        when (lifecycleState) {
            LifecycleState.CONNECTED -> return
            LifecycleState.CLOSED -> throw SurfRabbitConnectionClosedException("connect")
            LifecycleState.NEW -> Unit
        }

        val consumer = client.newConsumer("main")
        try {
            val declaredQueueName = consumer.declareQueue(
                queue = api.pluginName,
                durable = true,
                exclusive = false,
                autoDelete = false
            ).queue

            mainConsumer = consumer
            queueName = declaredQueueName
            onConnected()
            lifecycleState = LifecycleState.CONNECTED
        } catch (throwable: Throwable) {
            try {
                consumer.close()
            } catch (cleanupFailure: Throwable) {
                cleanupFailure.rethrowIfFatal()
                throwable.addSuppressed(cleanupFailure)
            } finally {
                client.discardConsumer(consumer)
            }
            throwable.rethrowIfFatal()
            throw throwable
        }
    }

    /** Runs subclass setup as part of the atomic connection lifecycle transaction. */
    protected open suspend fun onConnected() = Unit

    override suspend fun disconnect(): Unit = lifecycleMutex.withLock {
        if (lifecycleState == LifecycleState.CLOSED) return
        lifecycleState = LifecycleState.CLOSED
        if (clientLazy.isInitialized()) {
            client.close()
        }
    }
}
