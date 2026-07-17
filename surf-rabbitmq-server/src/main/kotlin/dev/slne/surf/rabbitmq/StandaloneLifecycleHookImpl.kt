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
            try {
                StandaloneRabbitMqInstance.get().dataPath = dataPath
                RabbitMQCommonInstance.get().onLoad()
            } catch (failure: Throwable) {
                try {
                    SurfApiStandaloneBootstrap.shutdown()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
    }

    override suspend fun beforeConnect() {
        SurfApiStandaloneBootstrap.enable()
        try {
            RabbitMQCommonInstance.get().onEnable()
        } catch (failure: Throwable) {
            try {
                SurfApiStandaloneBootstrap.shutdown()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override suspend fun afterDisconnect() {
        try {
            RabbitMQCommonInstance.get().onDisable()
        } finally {
            SurfApiStandaloneBootstrap.shutdown()
        }
    }
}
