package dev.slne.surf.eventbus.credentials

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import org.redisson.misc.RedisURI

@InternalEventBusApi
interface RedisCredentialsProvider : CredentialsProvider {

    fun redisURI(): RedisURI

    // Annotated as well as the interface: the ABI filter excludes annotated declarations, and
    // the companion generated for `by redisProvider` is a separate class. Without this the
    // interface vanished from the dump while the companion kept re-exporting every one of its
    // methods - which is how org.redisson.misc.RedisURI stayed in the published surface.
    @InternalEventBusApi
    companion object : RedisCredentialsProvider by redisProvider {
        val INSTANCE get() = redisProvider
    }
}

private val redisProvider = requiredService<RedisCredentialsProvider>()
