package dev.slne.surf.rabbitmq.common.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.connection.amqpConfig
import dev.kourier.amqp.robust.createRobustAMQPConnection
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import kotlin.time.Duration.Companion.seconds

abstract class AbstractRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: RabbitMQConfig
) : RabbitMQConnection {
    override lateinit var connection: AMQPConnection
    override lateinit var channel: AMQPChannel

    protected lateinit var queueName: String

    protected val rabbitConfig = amqpConfig {
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

    override suspend fun connect() {
        connection = createRobustAMQPConnection(api.scope, rabbitConfig)
        channel = connection.openChannel()

        queueName = channel.queueDeclare {
            name = api.pluginName
            durable = true
        }.queueName
    }

    override suspend fun disconnect() {
        channel.close()
        connection.close()
    }
}