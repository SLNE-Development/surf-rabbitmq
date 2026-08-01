package dev.slne.surf.eventbus.redis.internal

import dev.slne.surf.eventbus.redis.util.InternalRedisAPI
import kotlinx.serialization.modules.SerializersModule
import org.redisson.misc.RedisURI

@InternalRedisAPI
data class RedissonConfigDetails(
    val redisURI: RedisURI,
    val serializerModule: SerializersModule,
    val pluginName: String
)
