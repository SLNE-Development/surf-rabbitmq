package dev.slne.surf.rabbitmq.common.exception

class ProtocolVersionMismatchException(
    val expected: Int,
    val actual: Int
): RuntimeException(
    "Protocol version mismatch. Expected: $expected, actual: $actual"
)