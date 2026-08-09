package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolChunkPacketSizeMismatchException(
    expected: Int,
    actual: Int,
) : SurfRabbitProtocolException("Chunked packet size mismatch: expected $expected, got $actual")
