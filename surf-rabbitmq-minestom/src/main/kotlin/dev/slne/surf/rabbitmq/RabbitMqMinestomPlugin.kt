package dev.slne.surf.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.minestom.lobby.api.plugin.MinestomPlugin
import dev.slne.minestom.lobby.api.plugin.annotation.MinestomPluginMeta

@AutoService(MinestomPlugin::class)
@MinestomPluginMeta(
    "surf-rabbitmq-minestom",
    dependsOn = ["surf-api-minestom"]
)
class RabbitMqMinestomPlugin : MinestomPlugin(RabbitMqMinestomEntrypoint::class.java)