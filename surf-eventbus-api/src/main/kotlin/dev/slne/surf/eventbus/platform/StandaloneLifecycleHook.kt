package dev.slne.surf.eventbus.platform

import dev.slne.surf.eventbus.InternalEventBusApi
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * What a standalone process does around connect and disconnect.
 *
 * On Paper and Velocity the platform owns the lifecycle and this never runs.
 *
 * There is no `requiredService` here any more. Resolving one eagerly in a companion meant the
 * class could not initialise at all without a registration, which is why the bus's own unit
 * tests had to hand-write a `META-INF/services` entry for a no-op double: running
 * `@AutoService`'s processor next to this project's own on one `kspTest` task hits a KSP2
 * analysis-API lifetime bug. Absence is now simply [NoOp].
 */
@InternalEventBusApi
interface StandaloneLifecycleHook {
    fun onInit(dataPath: Path)
    suspend fun beforeConnect()
    suspend fun afterDisconnect()

    /** The hook a process with nothing to bootstrap gets. */
    @InternalEventBusApi
    object NoOp : StandaloneLifecycleHook {
        override fun onInit(dataPath: Path) = Unit
        override suspend fun beforeConnect() = Unit
        override suspend fun afterDisconnect() = Unit
    }

    @InternalEventBusApi
    companion object {
        /** The registered hook, or `null` when this process registered none. */
        fun discover(): StandaloneLifecycleHook? =
            ServiceLoader.load(StandaloneLifecycleHook::class.java).firstOrNull()
    }
}
