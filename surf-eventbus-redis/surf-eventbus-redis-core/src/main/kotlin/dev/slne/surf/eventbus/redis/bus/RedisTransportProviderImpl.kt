package dev.slne.surf.eventbus.redis.bus

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.RedisTransportProvider
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport

/**
 * Discovered via `ServiceLoader` from `RedisTransportLocator`.
 *
 * The [RedisApi] behind both transports is created lazily and shared between them; its lifecycle
 * moves under [dev.slne.surf.eventbus.core.SurfEventBusImpl] in Plan 3 Task 8.
 */
@AutoService(RedisTransportProvider::class)
class RedisTransportProviderImpl : RedisTransportProvider {

    private val redis by lazy { RedisApi.create().apply { freezeAndConnect() } }

    override fun event(): EventTransport = RedisEventTransport(redis, redis.json)

    override fun query(): QueryTransport = throw NotImplementedError(
        "the Redis query transport arrives in Plan 3 Task 7"
    )
}
