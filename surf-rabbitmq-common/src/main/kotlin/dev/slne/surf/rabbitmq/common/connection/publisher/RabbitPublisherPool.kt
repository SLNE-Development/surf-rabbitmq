package dev.slne.surf.rabbitmq.common.connection.publisher

import com.rabbitmq.client.AMQP
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import java.util.concurrent.atomic.AtomicInteger

class RabbitPublisherPool(
    connectionProvider: RabbitConnectionProvider,
    size: Int,
    options: RabbitPublisherOptions = RabbitPublisherOptions()
): AutoCloseable {

    init {
        require(size in 1..64) {
            "Publisher pool size must be in 1..64"
        }
    }

    private val publishers: Array<RabbitPublisher> = Array(size) { i ->
        RabbitPublisher(connectionProvider, i.toString(), options)
    }

    private val nextPublisher = AtomicInteger()

    suspend fun publish(
        exchange: String,
        body: ByteArray,
        routingKey: String = "",
        properties: AMQP.BasicProperties? = null,
        mandatory: Boolean = false
    ) {
        selectPublisher().publish(exchange, body, routingKey, properties, mandatory)
    }

    private fun selectPublisher(): RabbitPublisher {
        return publishers[Math.floorMod(nextPublisher.getAndIncrement(), publishers.size)]
    }

    override fun close() {
        var failure: Throwable? = null
        publishers.forEach { publisher ->
            try {
                publisher.close()
            } catch (throwable: Throwable) {
                throwable.rethrowIfFatal()
                val previous = failure
                if (previous == null) {
                    failure = throwable
                } else {
                    previous.addSuppressed(throwable)
                }
            }
        }
        failure?.let { throw it }
    }
}
