package dev.slne.surf.rabbitmq.common.connection.publisher

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitPublishException
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        val attempts = options.maxAttempts.coerceAtLeast(1)

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

                lastError = e

                withContext(dispatcher) {
                    resetChannel()
                }

                val hasNextAttempt = attempt < attempts - 1
                if (hasNextAttempt) {
                    delay(options.retryDelayMillis)
                }
            }
        }

        throw SurfRabbitPublishException(attempts, lastError)
    }

    private fun getChannel(): Channel {
        val existing = this.channel
        if (existing != null && existing.isOpen) {
            return existing
        }

        val newChannel = connectionProvider.createChannel()

        if (options.confirmPublishes) {
            newChannel.confirmSelect()
        }

        this.channel = newChannel

        return newChannel
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