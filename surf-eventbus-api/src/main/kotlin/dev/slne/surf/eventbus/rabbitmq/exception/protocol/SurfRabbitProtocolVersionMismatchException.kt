package dev.slne.surf.eventbus.rabbitmq.exception.protocol

import dev.slne.surf.eventbus.rabbitmq.exception.serialization.SurfRabbitSerializationException

class SurfRabbitProtocolVersionMismatchException(
    expected: Int,
    actual: Int,
) : SurfRabbitSerializationException(
        "Protocol version mismatch: expected $expected but received $actual",
    )
