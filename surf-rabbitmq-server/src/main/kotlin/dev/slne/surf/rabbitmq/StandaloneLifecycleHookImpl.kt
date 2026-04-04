package dev.slne.surf.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.api.standalone.SurfApiStandaloneBootstrap
import dev.slne.surf.rabbitmq.api.internal.StandaloneLifecycleHook
import kotlinx.coroutines.runBlocking

@AutoService(StandaloneLifecycleHook::class)
class StandaloneLifecycleHookImpl : StandaloneLifecycleHook {
    override fun onInit() {
        runBlocking {
            SurfApiStandaloneBootstrap.bootstrap()
        }
    }

    override suspend fun beforeConnect() {
        SurfApiStandaloneBootstrap.enable()
    }

    override suspend fun afterDisconnect() {
        SurfApiStandaloneBootstrap.shutdown()
    }
}