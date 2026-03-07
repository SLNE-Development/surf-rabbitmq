@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.wrapper

import dev.kourier.amqp.Table
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.connection.amqpConfig
import dev.kourier.amqp.connection.createAMQPConnection
import dev.slne.surf.rabbitmq.wrapper.config.RabbitMQConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RabbitMQApiImpl internal constructor(
    override val scope: CoroutineScope,
    configDirectory: Path
) : RabbitMQApi {
    override val config = RabbitMQConfig.create(configDirectory)
    override val rabbitConfig = amqpConfig {
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

    override lateinit var connection: AMQPConnection
        private set

    override lateinit var channel: AMQPChannel
        private set

    override suspend fun connect() {
        connection = createAMQPConnection(scope, rabbitConfig)
        channel = connection.openChannel()

        channel.basicQos(1u, false)
    }

    override suspend fun disconnect() {
        channel.close()
        connection.close()
    }

    override suspend fun declareQueue(
        queueName: String,
        durable: Boolean,
        exclusive: Boolean,
        autoDelete: Boolean,
    ) {
        channel.queueDeclare(queueName, durable, exclusive, autoDelete)
    }

    override suspend fun declareCallbackQueue(
        queueName: String,
        callbackQueueName: String,
        durable: Boolean
    ) {
        declareQueue(
            queueName = queueName,
            durable = durable,
            exclusive = false,
            autoDelete = false
        )

        declareQueue(
            queueName = callbackQueueName,
            durable = durable,
            exclusive = true,
            autoDelete = true
        )
    }

    override suspend fun declareExchange(
        exchangeName: String,
        type: String,
        durable: Boolean,
        autoDelete: Boolean,
        internal: Boolean,
        arguments: Table
    ) {
        channel.exchangeDeclare(
            exchangeName,
            type,
            durable,
            autoDelete,
            internal,
            arguments
        )
    }
}