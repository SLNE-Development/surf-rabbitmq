package dev.slne.surf.eventbus.credentials

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import org.redisson.misc.RedisURI

@InternalEventBusApi
interface RedisCredentialsProvider : CredentialsProvider {

    fun redisURI(): RedisURI

    companion object : RedisCredentialsProvider by redisProvider {
        val INSTANCE get() = redisProvider
    }
}

private val redisProvider = requiredService<RedisCredentialsProvider>()
