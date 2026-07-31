package dev.slne.surf.eventbus.redis.bus

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.RedisTransportProvider
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Discovered via `ServiceLoader` from `RedisTransportLocator`.
 *
 * The [RedisApi] behind both transports is shared between them, created here but connected only
 * when the first of them actually needs it - `event()`/`query()` run at `withRedis()` time, long
 * before `SurfEventBus.connect()`. `freeze()` happens on that same first call: nothing here
 * registers sync structures or event codecs that would need to run before it.
 */
@AutoService(RedisTransportProvider::class)
class RedisTransportProviderImpl : RedisTransportProvider {

    private val redis = RedisApi.create()
    private val connectMutex = Mutex()

    private suspend fun ensureConnected() {
        if (redis.isConnected()) return

        connectMutex.withLock {
            if (redis.isConnected()) return@withLock
            if (!redis.isFrozen()) redis.freeze()
            redis.connect()
        }
    }

    override fun event(): EventTransport = RedisEventTransport(redis, redis.json, ::ensureConnected)

    override fun query(): QueryTransport = RedisQueryTransport(redis, redis.json, ::ensureConnected)
}
