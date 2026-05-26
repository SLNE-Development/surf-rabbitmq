package dev.slne.surf.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.api.standalone.SurfApiStandaloneBootstrap
import dev.slne.surf.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.rabbitmq.common.RabbitMQInstance
import kotlinx.coroutines.runBlocking

@AutoService(StandaloneLifecycleHook::class)
class StandaloneLifecycleHookImpl : StandaloneLifecycleHook {
    override fun onInit() {
        runBlocking {
            SurfApiStandaloneBootstrap.bootstrap()
            RabbitMQInstance.get().onLoad()
        }
    }

    override suspend fun beforeConnect() {
        SurfApiStandaloneBootstrap.enable()
        RabbitMQInstance.get().onEnable()
    }

    override suspend fun afterDisconnect() {
        RabbitMQInstance.get().onDisable()
        SurfApiStandaloneBootstrap.shutdown()
    }
}