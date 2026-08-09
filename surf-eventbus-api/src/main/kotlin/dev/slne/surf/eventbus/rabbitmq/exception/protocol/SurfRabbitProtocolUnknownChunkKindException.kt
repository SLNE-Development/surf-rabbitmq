package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolUnknownChunkKindException(
    kind: Byte,
) : SurfRabbitProtocolException("Unknown chunk kind: $kind")
