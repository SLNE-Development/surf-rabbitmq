package dev.slne.surf.eventbus.rabbitmq.publisher

import com.rabbitmq.client.AMQP
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitConnectionProvider
import dev.slne.surf.eventbus.rabbitmq.connection.ReturnListenerBridge
import java.util.concurrent.atomic.AtomicInteger

class RabbitPublisherPool(
    connectionProvider: RabbitConnectionProvider,
    size: Int,
    options: RabbitPublisherOptions = RabbitPublisherOptions()
): AutoCloseable {

    init {
        require(size > 0) {
            "Publisher pool size must be greater than 0"
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
        mandatory: Boolean = false,
        expectedConnectionGeneration: Long? = null
    ) {
        selectPublisher().publish(exchange, body, routingKey, properties, mandatory, expectedConnectionGeneration)
    }

    private fun selectPublisher(): RabbitPublisher {
        return publishers[Math.floorMod(nextPublisher.getAndIncrement(), publishers.size)]
    }

    /** Forwards [listener] to every publisher in the pool. */
    fun setReturnListener(listener: ReturnListenerBridge) {
        publishers.forEach { it.setReturnListener(listener) }
    }

    override fun close() {
        publishers.forEach { publisher ->
            runCatching {
                publisher.close()
            }
        }
    }
}