package dev.slne.surf.eventbus.rabbitmq.connection

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitMQConnectionFactory

@AutoService(RabbitMQConnectionFactory::class)
class RabbitConnectionFactoryImpl : RabbitMQConnectionFactory {
    override fun createConnection(api: SurfRabbitApi): RabbitMQConnection = RabbitConnectionImpl(api)
}
