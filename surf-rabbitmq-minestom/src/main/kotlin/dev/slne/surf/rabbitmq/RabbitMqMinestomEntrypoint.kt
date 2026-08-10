package dev.slne.surf.rabbitmq

import com.google.inject.Inject
import com.google.inject.Singleton
import dev.slne.minestom.lobby.api.plugin.MinestomPluginEntrypoint
import dev.slne.minestom.lobby.api.plugin.annotation.DataDirectory
import dev.slne.surf.rabbitmq.common.RabbitMQCommonInstance
import java.nio.file.Path

@Singleton
class RabbitMqMinestomEntrypoint @Inject constructor(
    @DataDirectory path: Path
) : MinestomPluginEntrypoint {

    init {
        dataPath = path
    }

    override suspend fun start() {
        RabbitMQCommonInstance.get().onLoad()
        RabbitMQCommonInstance.get().onEnable()
    }

    override suspend fun stop() {
        RabbitMQCommonInstance.get().onDisable()
    }

    companion object {
        lateinit var dataPath: Path
    }
}