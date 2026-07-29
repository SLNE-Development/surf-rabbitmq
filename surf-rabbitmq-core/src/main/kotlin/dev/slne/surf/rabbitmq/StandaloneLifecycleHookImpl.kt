package dev.slne.surf.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.api.standalone.SurfApiStandaloneBootstrap
import dev.slne.surf.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.rabbitmq.common.RabbitMQCommonInstance
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

@AutoService(StandaloneLifecycleHook::class)
class StandaloneLifecycleHookImpl : StandaloneLifecycleHook {
    override fun onInit(dataPath: Path) {
        runBlocking {
            SurfApiStandaloneBootstrap.bootstrap()

            StandaloneRabbitMqInstance.get().dataPath = dataPath
            RabbitMQCommonInstance.get().onLoad()
        }
    }

    override suspend fun beforeConnect() {
        SurfApiStandaloneBootstrap.enable()
        RabbitMQCommonInstance.get().onEnable()
    }

    override suspend fun afterDisconnect() {
        RabbitMQCommonInstance.get().onDisable()
        SurfApiStandaloneBootstrap.shutdown()
    }
}