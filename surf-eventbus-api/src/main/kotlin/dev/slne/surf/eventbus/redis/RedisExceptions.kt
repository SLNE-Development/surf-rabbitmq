package dev.slne.surf.eventbus.redis

import dev.slne.surf.eventbus.exception.SurfEventBusException

/**
 * The root of everything the Redis side throws on purpose.
 *
 * Rabbit had `SurfRabbitException` and Redis had nothing, so a consumer catching "the bus
 * failed" caught half of it.
 */
abstract class SurfRedisException(message: String, cause: Throwable? = null) :
    SurfEventBusException(message, cause)
