package dev.slne.surf.eventbus.platform.standalone

import com.google.auto.service.AutoService
import dev.slne.surf.api.standalone.SurfApiStandaloneBootstrap
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Boots surf-api-standalone around the bus lifecycle.
 *
 * Lives here rather than in `surf-eventbus-core`: it is standalone-specific, and its
 * `@AutoService` registration in core meant core's own unit tests discovered it — along with
 * the standalone platform instance it drove — and read a `dataPath` nothing had set.
 */
@AutoService(StandaloneLifecycleHook::class)
class StandaloneLifecycleHookImpl : StandaloneLifecycleHook {
    override fun onInit(dataPath: Path) {
        runBlocking {
            SurfApiStandaloneBootstrap.bootstrap()

            StandaloneEventBusInstance.configure(StandaloneEventBusInstance.serviceName, dataPath)
            StandaloneEventBusInstance.get().start()
        }
    }

    override suspend fun beforeConnect() {
        SurfApiStandaloneBootstrap.enable()
    }

    override suspend fun afterDisconnect() {
        StandaloneEventBusInstance.get().shutdown()
        SurfApiStandaloneBootstrap.shutdown()
    }
}
