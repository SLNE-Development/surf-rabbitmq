package dev.slne.surf.eventbus.rabbitmq.publisher

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitPublishException
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitConnectionGenerationChangedException
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitConnectionProvider
import dev.slne.surf.eventbus.rabbitmq.connection.ReturnListenerBridge
import kotlinx.coroutines.*
import java.lang.AutoCloseable
import java.util.concurrent.Executors

class RabbitPublisher(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    private val options: RabbitPublisherOptions = RabbitPublisherOptions()
) : AutoCloseable {

    private data class PublisherChannel(
        val channel: Channel,
        val connectionGeneration: Long
    )

    private val dispatcher: CoroutineDispatcher = Executors
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
        val publisherChannel = try {
            withTimeout(options.recoveryTimeout) {
                obtainChannel(expectedConnectionGeneration)
            }
        } catch (cause: CancellationException) {
            if (cause !is TimeoutCancellationException) {
                throw cause
            }

            throw SurfRabbitPublishException(
                "Timed out after ${options.recoveryTimeout} while waiting for a RabbitMQ publisher channel",
                cause
            )
        } catch (cause: Throwable) {
            throw SurfRabbitPublishException(
                "Could not obtain a RabbitMQ publisher channel",
                cause
            )
        }

        try {
            withTimeout(options.operationTimeout) {
                withContext(dispatcher) {
                    connectionProvider.requireOpenGeneration(
                        publisherChannel.connectionGeneration
                    )

                    publisherChannel.channel.basicPublish(
                        exchange,
                        routingKey,
                        mandatory,
                        properties,
                        body
                    )

                    if (options.confirmPublishes) {
                        publisherChannel.channel.waitForConfirmsOrDie(
                            options.confirmTimeout.inWholeMilliseconds
                        )
                    }
                }
            }
        } catch (cause: CancellationException) {
            resetChannelSafely()

            if (cause !is TimeoutCancellationException) {
                throw cause
            }

            throw SurfRabbitPublishException(
                "RabbitMQ publish timed out after ${options.operationTimeout}",
                cause
            )
        } catch (cause: Throwable) {
            resetChannelSafely()

            throw SurfRabbitPublishException(
                "Could not publish RabbitMQ message",
                cause
            )
        }
    }

    private suspend fun obtainChannel(
        expectedConnectionGeneration: Long?
    ): PublisherChannel {
        var lastFailure: Throwable? = null
        val attempts = options.channelOpenAttempts.coerceAtLeast(1)

        repeat(attempts) { attempt ->
            try {
                val connectionGeneration = connectionProvider.awaitOpen(
                    expectedConnectionGeneration
                )

                val channel = withContext(dispatcher) {
                    getChannel(connectionGeneration)
                }

                return PublisherChannel(
                    channel = channel,
                    connectionGeneration = connectionGeneration
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: RabbitConnectionGenerationChangedException) {
                throw cause
            } catch (cause: Throwable) {
                lastFailure = cause

                withContext(dispatcher) {
                    resetChannel()
                }

                if (attempt < attempts - 1) {
                    delay(options.channelRetryDelay)
                }
            }
        }

        throw SurfRabbitPublishException(
            attempts = attempts,
            cause = lastFailure
        )
    }

    private fun getChannel(connectionGeneration: Long): Channel {
        val existing = channel

        if (existing != null && existing.isOpen) {
            return existing
        }

        resetChannel()

        return connectionProvider
            .createChannel(connectionGeneration)
            .also { created ->
                if (options.confirmPublishes) {
                    created.confirmSelect()
                }
                returnListener?.install(created)

                channel = created
            }
    }

    private suspend fun resetChannelSafely() {
        withContext(dispatcher) {
            resetChannel()
        }
    }

    private fun resetChannel() {
        val current = channel
        channel = null

        runCatching {
            current?.close()
        }
    }

    override fun close() {
        try {
            runBlocking(dispatcher) {
                resetChannel()
            }
        } finally {
            (dispatcher as? AutoCloseable)?.close()
        }
    }

    override fun toString(): String {
        return "RabbitPublisher(name='$name', options=$options)"
    }
}