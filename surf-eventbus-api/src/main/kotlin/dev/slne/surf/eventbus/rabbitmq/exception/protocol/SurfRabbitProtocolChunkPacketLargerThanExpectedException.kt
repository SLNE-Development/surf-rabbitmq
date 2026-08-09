package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolChunkPacketLargerThanExpectedException(
    max: Int,
    actual: Int,
    offset: Int,
    chunkSize: Int,
) : SurfRabbitProtocolException(
        "Chunked packet is larger than expected: expected at most $max bytes, got $actual bytes (offset=$offset, chunkSize=$chunkSize)",
    )
