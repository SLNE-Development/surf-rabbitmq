package dev.slne.surf.rabbitmq

import dev.slne.surf.api.minestom.plugin.SurfMinestomPlugin
import dev.slne.surf.rabbitmq.common.RabbitMQCommonInstance

object RabbitMqMinestomPlugin : SurfMinestomPlugin() {
    override suspend fun onEnable() {
        RabbitMQCommonInstance.get().onLoad()
        RabbitMQCommonInstance.get().onEnable()
    }

    override suspend fun onDisable() {
        RabbitMQCommonInstance.get().onDisable()
    }
}