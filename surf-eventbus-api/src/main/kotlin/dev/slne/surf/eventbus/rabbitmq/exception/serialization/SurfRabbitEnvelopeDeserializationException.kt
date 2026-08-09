package dev.slne.surf.eventbus.rabbitmq.exception.serialization

class SurfRabbitEnvelopeDeserializationException(
    cause: Throwable? = null,
) : SurfRabbitSerializationException("Failed to deserialize envelope — data may be corrupt", cause)
