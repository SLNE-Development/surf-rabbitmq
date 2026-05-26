package dev.slne.surf.rabbitmq.common.connection

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitConsumer

abstract class AbstractRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: RabbitMQConfig,
) : RabbitMQConnection {
    val client = RabbitClient.create(config, api.pluginName)

    protected lateinit var queueName: String
        private set

    protected lateinit var mainConsumer: RabbitConsumer
        private set

    override suspend fun connect() {
        mainConsumer = client.newConsumer("main")

        queueName = mainConsumer.declareQueue(
            queue = api.pluginName,
            durable = true,
            exclusive = false,
            autoDelete = false
        ).queue
    }

    override suspend fun disconnect() {
        client.close()
    }
}