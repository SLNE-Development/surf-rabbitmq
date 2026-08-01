package dev.slne.surf.eventbus

import dev.slne.surf.api.core.util.requiredService
import java.nio.file.Path

/**
 * Finds the [SurfEventBus] implementation on the classpath.
 *
 * `SurfEventBus.builder(...)` must not reference `surf-eventbus-bus-core` directly - this module
 * is the api surface and must stay implementation-free, the same way `surf-eventbus-rabbitmq-api`
 * and `surf-eventbus-redis-api` do. A `ServiceLoader`-backed lookup (mirroring
 * [RedisTransportProvider]) is the smallest thing that inverts that dependency.
 */
interface SurfEventBusFactory {
    fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder

    companion object {
        val instance by lazy { requiredService<SurfEventBusFactory>() }
    }
}
