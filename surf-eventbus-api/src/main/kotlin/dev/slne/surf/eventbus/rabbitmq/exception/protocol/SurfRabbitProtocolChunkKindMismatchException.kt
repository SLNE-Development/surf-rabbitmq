package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolChunkKindMismatchException(
    expected: String,
    actual: String,
) : SurfRabbitProtocolException("Chunked packet kind mismatch: expected $expected, got $actual")
