package dev.slne.surf.eventbus.rabbitmq.core.connection

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.api.connection.RabbitMQConnectionFactory

@AutoService(RabbitMQConnectionFactory::class)
class RabbitConnectionFactoryImpl : RabbitMQConnectionFactory {
    override fun createConnection(api: SurfRabbitApi): RabbitMQConnection = RabbitConnectionImpl(api)
}
