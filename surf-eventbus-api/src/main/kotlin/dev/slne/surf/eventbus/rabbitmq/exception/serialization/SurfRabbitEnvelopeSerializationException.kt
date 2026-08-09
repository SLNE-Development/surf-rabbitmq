package dev.slne.surf.eventbus.rabbitmq.exception.serialization

class SurfRabbitEnvelopeSerializationException(
    cause: Throwable? = null,
) : SurfRabbitSerializationException("Failed to serialize envelope", cause)
