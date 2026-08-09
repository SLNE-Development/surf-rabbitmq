package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolChunkMetadataMismatchException(
    correlationId: String,
    field: String,
    expected: Any?,
    actual: Any?,
) : SurfRabbitProtocolException(
        "Mismatching chunk metadata for correlationId $correlationId: " +
            "$field expected $expected, got $actual",
    )
