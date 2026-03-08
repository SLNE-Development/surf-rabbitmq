package dev.slne.surf.rabbitmq.api.exception

open class SurfRabbitSerializationException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitSerializerNotFoundException(className: String) :
    SurfRabbitSerializationException("No serializer found for class '$className'")

class SurfRabbitEnvelopeDeserializationException(cause: Throwable? = null) :
    SurfRabbitSerializationException("Failed to deserialize envelope — data may be corrupt", cause)

class SurfRabbitProtocolVersionMismatchException(expected: Int, actual: Int) :
    SurfRabbitSerializationException(
        "Protocol version mismatch: expected $expected but received $actual"
    )
