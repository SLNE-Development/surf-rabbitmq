package dev.slne.surf.rabbitmq.client.connection

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnectionFactory

@AutoService(RabbitMQConnectionFactory::class)
class ClientRabbitMQConnectionFactory : RabbitMQConnectionFactory {
    override fun createConnection(api: RabbitMQApi): RabbitMQConnection {
        return ClientRabbitMQConnectionImpl(api, api.config)
    }
}