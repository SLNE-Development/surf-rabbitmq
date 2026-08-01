package dev.slne.surf.eventbus.core.testing

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.internal.StandaloneLifecycleHook
import java.nio.file.Path

/**
 * A no-op [StandaloneLifecycleHook] for the bus's own unit tests.
 *
 * Pulling in `surf-eventbus-rabbitmq-core` wholesale (for the real
 * `StandaloneLifecycleHookImpl`) would also register its `StandaloneRabbitMqInstance`, which
 * `SurfRabbitApiBuilder.build()` would then find via `ServiceLoader` and treat as a real
 * platform - reading its never-initialized `dataPath` and failing with
 * `UninitializedPropertyAccessException`. This test double provides just the one service the
 * bus needs, without that side effect.
 *
 * Registered by hand in `META-INF/services` rather than via `@AutoService`: running that
 * processor alongside `surfEventbusKsp` on the same `kspTest` task triggers a KSP2
 * analysis-API lifetime bug (`KaInvalidLifetimeOwnerAccessException`) in this Kotlin version.
 */
@OptIn(InternalEventBusApi::class)
class FakeStandaloneLifecycleHook : StandaloneLifecycleHook {
    override fun onInit(dataPath: Path) = Unit
    override suspend fun beforeConnect() = Unit
    override suspend fun afterDisconnect() = Unit
}
