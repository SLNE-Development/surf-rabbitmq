package dev.slne.surf.rabbitmq.wrapper.queue

import dev.kourier.amqp.Field

fun rabbitQueue(
    queueName: String,
    durable: Boolean = RabbitMQQueue.DEFAULT_DURABLE,
    exclusive: Boolean = RabbitMQQueue.DEFAULT_EXCLUSIVE,
    autoDelete: Boolean = RabbitMQQueue.DEFAULT_AUTO_DELETE,
    arguments: Map<String, Field> = RabbitMQQueue.DEFAULT_ARGUMENTS
) = RabbitMQQueue(
    queueName,
    durable,
    exclusive,
    autoDelete,
    arguments,
)

data class RabbitMQQueue(
    val queueName: String,
    val durable: Boolean = DEFAULT_DURABLE,
    val exclusive: Boolean = DEFAULT_EXCLUSIVE,
    val autoDelete: Boolean = DEFAULT_AUTO_DELETE,
    val arguments: Map<String, Field> = DEFAULT_ARGUMENTS
) {
    companion object {
        const val DEFAULT_DURABLE = true
        const val DEFAULT_EXCLUSIVE = false
        const val DEFAULT_AUTO_DELETE = false
        val DEFAULT_ARGUMENTS = emptyMap<String, Field>()
    }
}
