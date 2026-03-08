package dev.slne.surf.rabbitmq.api.exception

open class SurfRabbitConnectionException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitConnectionFailedException(host: String, port: Int, cause: Throwable? = null) :
    SurfRabbitConnectionException("Failed to connect to RabbitMQ broker at $host:$port", cause)

class SurfRabbitConnectionClosedException(operation: String) :
    SurfRabbitConnectionException("Cannot perform '$operation' on a closed connection")
