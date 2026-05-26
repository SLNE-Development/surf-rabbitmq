package dev.slne.surf.rabbitmq.common.connection.publisher

import com.rabbitmq.client.AMQP
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
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
        mandatory: Boolean = false
    ) {
        selectPublisher().publish(exchange, body, routingKey, properties, mandatory)
    }

    private fun selectPublisher(): RabbitPublisher {
        return publishers[Math.floorMod(nextPublisher.getAndIncrement(), publishers.size)]
    }

    override fun close() {
        publishers.forEach { publisher ->
            runCatching {
                publisher.close()
            }
        }
    }
}