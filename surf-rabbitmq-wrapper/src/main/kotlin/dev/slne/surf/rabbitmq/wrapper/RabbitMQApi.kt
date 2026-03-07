@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.wrapper

import dev.kourier.amqp.Properties
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.connection.amqpConfig
import dev.kourier.amqp.connection.createAMQPConnection
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.wrapper.config.RabbitMQConfig
import dev.slne.surf.rabbitmq.wrapper.queue.RabbitMQQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

class RabbitMQApi internal constructor(
    private val scope: CoroutineScope,
    configDirectory: Path,
    private val queues: MutableSet<RabbitMQQueue>
) {
    private val config = RabbitMQConfig.create(configDirectory)
    private val rabbitConfig = amqpConfig {
        server {
            host = config.host
            port = config.port
            user = config.username
            password = config.password
            vhost = config.vhost
            timeout = config.timeout.seconds
            connectionName = config.connectionName
        }
    }

    private lateinit var connection: AMQPConnection
    private lateinit var channel: AMQPChannel

    suspend fun connect() {
        connection = createAMQPConnection(scope, rabbitConfig)
        channel = connection.openChannel()

        setupQueues()
        setupQos()
    }

    suspend fun declareQueue(queue: RabbitMQQueue) = channel.queueDeclare(
        queue.queueName,
        queue.durable,
        queue.exclusive,
        queue.autoDelete,
        queue.arguments
    )

    suspend fun setupQueues() {
        queues.forEach { queue ->
            declareQueue(queue)
        }
    }

    private fun ensureQueueExists(queueName: String) {
        require(queues.any { queue -> queue.queueName == queueName }) {
            "Queue $queueName not found."
        }
    }


    private suspend fun setupQos(
        amount: UShort = 1u,
        global: Boolean = false
    ) {
        channel.basicQos(amount, global)
    }

    suspend fun consume(
        queueName: String,
        exclusive: Boolean = false,
    ) {
        val consumer = channel.basicConsume(
            queueName,
            noAck = false,
            exclusive = exclusive
        )

        for (delivery in consumer) {
            channel.basicAck(delivery.message, multiple = false)
        }
    }

    suspend fun <T : Any> publish(
        messageClass: KClass<T>,
        message: T,
        queueName: String,
        exchange: String = "",
        mandatory: Boolean = false,
        immediate: Boolean = false,
        properties: Properties = properties {
            deliveryMode = 2u // Persistent message by default, only works with durable queues
        }
    ) {
        ensureQueueExists(queueName)

        channel.basicPublish(
            RabbitMQSerializer.PROTO.encodeToByteArray(messageClass.serializer(), message),
            exchange,
            queueName,
            mandatory,
            immediate,
            properties
        )
    }

    suspend inline fun <reified T : Any> publish(
        message: T,
        queueName: String,
        exchange: String = "",
        mandatory: Boolean = false,
        immediate: Boolean = false,
        properties: Properties = Properties()
    ) = publish(T::class, message, queueName, exchange, mandatory, immediate, properties)

    suspend fun disconnect() {
        channel.close()
        connection.close()
    }
}