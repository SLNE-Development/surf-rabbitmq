package dev.slne.surf.eventbus.rabbitmq.config.migration.plugin

import dev.slne.surf.api.core.config.migration.ConfigMigration
import dev.slne.surf.eventbus.InternalEventBusApi
import org.spongepowered.configurate.ConfigurationNode

@InternalEventBusApi
object ClearCompleteConfigPluginMigration : ConfigMigration {
    override fun migrate(node: ConfigurationNode) {
        node.raw(null)
    }
}