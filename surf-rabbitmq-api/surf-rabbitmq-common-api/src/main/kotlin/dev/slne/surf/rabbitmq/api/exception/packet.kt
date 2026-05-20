package dev.slne.surf.rabbitmq.api.exception

open class SurfRabbitProtocolException(message: String, cause: Throwable? = null) : SurfRabbitException(message, cause)

class SurfRabbitProtocolUnknownChunkKindException(kind: Byte) : SurfRabbitProtocolException("Unknown chunk kind: $kind")
class SurfRabbitProtocolMissingChunkException : SurfRabbitProtocolException("Missing chunk while assembling packet")

class SurfRabbitProtocolChunkPacketLargerThanExpectedException(max: Int, actual: Int, offset: Int, chunkSize: Int) :
    SurfRabbitProtocolException("Chunked packet is larger than expected: expected at most $max bytes, got $actual bytes (offset=$offset, chunkSize=$chunkSize)")

class SurfRabbitProtocolChunkPacketSizeMismatchException(expected: Int, actual: Int) :
    SurfRabbitProtocolException("Chunked packet size mismatch: expected $expected, got $actual")

class SurfRabbitProtocolChunkKindMismatchException(expected: String, actual: String) :
    SurfRabbitProtocolException("Chunked packet kind mismatch: expected $expected, got $actual")

class SurfRabbitProtocolInvalidChunkMetadataException(
    field: String,
    expected: String,
    actual: Any?
) : SurfRabbitProtocolException(
    "Invalid chunk metadata for '$field': expected $expected, got $actual"
)

class SurfRabbitProtocolChunkMetadataMismatchException(
    correlationId: String,
    field: String,
    expected: Any?,
    actual: Any?
) : SurfRabbitProtocolException(
    "Mismatching chunk metadata for correlationId $correlationId: " +
            "$field expected $expected, got $actual"
)