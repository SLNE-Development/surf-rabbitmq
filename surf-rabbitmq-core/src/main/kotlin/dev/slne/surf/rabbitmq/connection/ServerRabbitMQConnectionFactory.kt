package dev.slne.surf.rabbitmq.connection

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnectionFactory

@AutoService(RabbitMQConnectionFactory::class)
class ServerRabbitMQConnectionFactory : RabbitMQConnectionFactory {
    override fun createConnection(api: RabbitMQApi): RabbitMQConnection {
        return ServerRabbitMQConnectionImpl(api, api.config)
    }
}