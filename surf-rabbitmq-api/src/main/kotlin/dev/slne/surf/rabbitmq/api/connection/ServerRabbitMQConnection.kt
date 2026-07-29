package dev.slne.surf.rabbitmq.api.connection

interface ServerRabbitMQConnection : RabbitMQConnection {
    fun registerRequestHandler(instance: Any)
}