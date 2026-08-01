package dev.slne.surf.eventbus.redis.credentials

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import org.redisson.misc.RedisURI

@InternalEventBusApi
interface RedisCredentialsProvider {

    fun redisURI(): RedisURI

    companion object : RedisCredentialsProvider by provider {
        val INSTANCE get() = provider
    }
}

private val provider = requiredService<RedisCredentialsProvider>()