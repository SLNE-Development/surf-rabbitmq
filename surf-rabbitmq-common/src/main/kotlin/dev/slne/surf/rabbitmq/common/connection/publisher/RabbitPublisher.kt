package dev.slne.surf.rabbitmq.common.connection.publisher

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionClosedException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitPublishException
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.AutoCloseable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

class RabbitPublisher(
    private val connectionProvider: RabbitConnectionProvider,
    private val name: String,
    private val options: RabbitPublisherOptions = RabbitPublisherOptions()
) : AutoCloseable {

    private val dispatcherThread = AtomicReference<Thread>()

    private val dispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "rabbit-publisher-$name").apply {
                isDaemon = true
                dispatcherThread.set(this)
            }
        }
        .asCoroutineDispatcher()

    private var channel: Channel? = null
    private val closed = AtomicBoolean()

    /**
     * @see Channel.basicPublish
     */
    suspend fun publish(
        exchange: String,
        body: ByteArray,
        routingKey: String = "",
        properties: AMQP.BasicProperties? = null,
        mandatory: Boolean = false,
    ) {
        if (closed.get()) throw SurfRabbitConnectionClosedException("publish")

        val attempts = options.maxAttempts

        var lastError: Throwable? = null

        repeat(attempts) { attempt ->
            try {
                withContext(dispatcher) {
                    val channel = getChannel()
                    channel.basicPublish(
                        exchange,
                        routingKey,
                        mandatory,
                        properties,
                        body
                    )

                    if (options.confirmPublishes) {
                        channel.waitForConfirmsOrDie(options.confirmTimeoutMillis)
                    }
                }

                return
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                e.rethrowIfFatal()
                if (e is SurfRabbitConnectionClosedException) throw e

                lastError = e

                try {
                    withContext(dispatcher) {
                        resetChannel()
                    }
                } catch (resetError: Throwable) {
                    resetError.rethrowIfFatal()
                    e.addSuppressed(resetError)
                }

                val hasNextAttempt = attempt < attempts - 1
                if (hasNextAttempt) {
                    if (closed.get()) throw SurfRabbitConnectionClosedException("publish")
                    delay(options.retryDelay)
                }
            }
        }

        throw SurfRabbitPublishException(attempts, lastError)
    }

    private fun getChannel(): Channel {
        if (closed.get()) throw SurfRabbitConnectionClosedException("publish")

        val existing = this.channel
        if (existing != null && existing.isOpen) {
            return existing
        }

        val newChannel = connectionProvider.createChannel()

        try {
            if (options.confirmPublishes) {
                newChannel.confirmSelect()
            }
        } catch (failure: Throwable) {
            try {
                newChannel.close()
            } catch (cleanupFailure: Throwable) {
                cleanupFailure.rethrowIfFatal()
                failure.addSuppressed(cleanupFailure)
            }
            failure.rethrowIfFatal()
            throw failure
        }

        this.channel = newChannel

        return newChannel
    }

    private fun resetChannel() {
        val currentChannel = channel
        channel = null
        currentChannel?.close()
    }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (Thread.currentThread() === dispatcherThread.get()) {
                resetChannel()
            } else {
                runBlocking(dispatcher) {
                    resetChannel()
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    override fun toString(): String {
        return "RabbitPublisher(name='$name', options=$options)"
    }
}
