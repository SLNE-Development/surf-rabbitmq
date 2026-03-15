package dev.slne.surf.rabbitmq.common.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.connection.AMQPConnection
import dev.kourier.amqp.connection.amqpConfig
import dev.kourier.amqp.robust.createRobustAMQPConnection
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.internal.Platform
import kotlin.time.Duration.Companion.seconds

abstract class AbstractRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: RabbitMQConfig,
    platform: Platform
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
            connectionName = api.pluginName + "_" + platform
        }
    }

    override suspend fun connect() {
        println("Connecting to RabbitMQ at ${config.host}:${config.port} with user ${config.username}")
        connection = createRobustAMQPConnection(api.scope, rabbitConfig)
        println("Connected to RabbitMQ at ${config.host}:${config.port}")
        channel = connection.openChannel()
        println("Opened channel to RabbitMQ at ${config.host}:${config.port}")

        queueName = channel.queueDeclare {
            name = api.pluginName
            durable = true
        }.queueName

        println("Declared queue $queueName for plugin ${api.pluginName}")
    }

    override suspend fun disconnect() {
        channel.close()
        connection.close()
    }
}