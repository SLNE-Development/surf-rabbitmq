package dev.slne.surf.eventbus.redis.bus

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.core.RedisTransportProvider
import dev.slne.surf.eventbus.core.RedisTransports
import dev.slne.surf.eventbus.redis.SurfRedisApi
import dev.slne.surf.eventbus.redis.config.redisSettingsFor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovered via `ServiceLoader` from `RedisTransportLocator`.
 *
 * The [SurfRedisApi] behind both transports is shared between them, created here but connected only
 * when the first of them actually needs it - `create()` runs at `withRedis()` time, long before
 * `SurfEventBus.connect()`. `freeze()` happens on that same first call: nothing here registers
 * sync structures or event codecs that would need to run before it.
 *
 * One bundle per consumer data folder, not one per process. The connection is opened from
 * settings resolved four-layered against that folder, so two plugins with different
 * `eventbus-plugin.yml` files get the connections they each asked for; two buses in the *same*
 * folder still share one, which is the sharing the old single instance was actually after.
 */
@AutoService(RedisTransportProvider::class)
class RedisTransportProviderImpl : RedisTransportProvider {
    private val bundles = ConcurrentHashMap<Path, RedisTransports>()

    override fun create(pluginDataPath: Path): RedisTransports =
        bundles.computeIfAbsent(pluginDataPath.toAbsolutePath().normalize()) { build(it) }

    private fun build(pluginDataPath: Path): RedisTransports {
        val redis =
            SurfRedisApi.create(
                settings = redisSettingsFor(pluginDataPath),
                pluginName = pluginDataPath.fileName?.toString() ?: UNATTRIBUTED_PLUGIN_NAME,
            )

        val connectMutex = Mutex()

        suspend fun ensureConnected() {
            if (redis.isConnected) return

            connectMutex.withLock {
                if (redis.isConnected) return@withLock
                if (!redis.isFrozen) redis.freeze()
                redis.connect()
            }
        }

        return RedisTransports(
            redisApi = redis,
            event = RedisEventTransport(redis, redis.json, ::ensureConnected),
            query = RedisQueryTransport(redis, redis.json, ::ensureConnected),
        )
    }

    private companion object {
        const val UNATTRIBUTED_PLUGIN_NAME = "surf-eventbus"
    }
}
