package dev.slne.surf.rabbitmq.common.connection.publisher

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitPublishException
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.core.connection.ReturnListenerBridge
import kotlinx.coroutines.*
import java.lang.AutoCloseable
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

class RabbitPublisher(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    private val options: RabbitPublisherOptions = RabbitPublisherOptions()
) : AutoCloseable {

    private val dispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "rabbit-publisher-$name").apply {
                isDaemon = true
            }
        }
        .asCoroutineDispatcher()

    private var channel: Channel? = null

    @Volatile
    private var returnListener: ReturnListenerBridge? = null

    /** Installs [listener] on this publisher's current channel, and every channel after it. */
    fun setReturnListener(listener: ReturnListenerBridge) {
        returnListener = listener
    }

    /**
     * @see Channel.basicPublish
     */
    suspend fun publish(
        exchange: String,
        body: ByteArray,
        routingKey: String = "",
        properties: AMQP.BasicProperties? = null,
        mandatory: Boolean = false,
        expectedConnectionGeneration: Long? = null
    ) {
        val completed = withTimeoutOrNull(options.operationTimeout) {
            connectionProvider.awaitOpen(expectedConnectionGeneration)

            val channel = obtainChannel(expectedConnectionGeneration)

            withContext(dispatcher) {
                connectionProvider.requireGeneration(expectedConnectionGeneration)

                try {
                    channel.basicPublish(
                        exchange,
                        routingKey,
                        mandatory,
                        properties,
                        body
                    )

                    if (options.confirmPublishes) {
                        channel.waitForConfirmsOrDie(options.confirmTimeout.inWholeMilliseconds)
                    }
                } catch (cause: Throwable) {
                    resetChannel()

                    if (cause is CancellationException) throw cause
                    throw SurfRabbitPublishException("RabbitMQ publish was not confirmed", cause)
                }
            }

            true
        }

        if (completed != true) {
            throw SurfRabbitPublishException("RabbitMQ publish timed out after ${options.operationTimeout}")
        }
    }

    private suspend fun obtainChannel(expectedGeneration: Long?): Channel {
        var lastFailure: Throwable? = null

        repeat(options.channelOpenAttempts.coerceAtLeast(1)) { attempt ->
            try {
                connectionProvider.awaitOpen(expectedGeneration)

                return withContext(dispatcher) {
                    getChannel(expectedGeneration)
                }
            } catch (cause: Throwable) {
                if (cause is CancellationException) {
                    throw cause
                }

                lastFailure = cause

                withContext(dispatcher) {
                    resetChannel()
                }

                if (attempt < options.channelOpenAttempts - 1) {
                    delay(options.channelRetryDelay)
                }
            }
        }

        throw SurfRabbitPublishException("Could not open a RabbitMQ publisher channel", lastFailure)
    }

    private fun getChannel(expectedGeneration: Long?): Channel {
        val existing = channel
        if (existing != null && existing.isOpen) {
            return existing
        }

        return connectionProvider
            .createChannel(expectedGeneration)
            .also { created ->
                if (options.confirmPublishes) {
                    created.confirmSelect()
                }
                returnListener?.install(created)

                channel = created
            }
    }

    private fun resetChannel() {
        runCatching {
            channel?.close()
        }

        channel = null
    }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        try {
            runBlocking(dispatcher) {
                resetChannel()
            }
        } finally {
            dispatcher.close()
        }
    }

    override fun toString(): String {
        return "RabbitPublisher(name='$name', options=$options)"
    }
}