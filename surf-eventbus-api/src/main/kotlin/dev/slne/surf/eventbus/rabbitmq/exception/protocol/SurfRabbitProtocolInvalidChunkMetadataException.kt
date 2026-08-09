package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolInvalidChunkMetadataException(
    field: String,
    expected: String,
    actual: Any?,
) : SurfRabbitProtocolException(
        "Invalid chunk metadata for '$field': expected $expected, got $actual",
    )
