package dev.slne.surf.eventbus.rabbitmq.exception.serialization

class SurfRabbitSerializerNotFoundException(
    className: String,
) : SurfRabbitSerializationException("No serializer found for class '$className'")
