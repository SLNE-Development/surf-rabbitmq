package dev.slne.surf.rabbitmq.api.internal.config.migration.plugin

import dev.slne.surf.api.core.config.migration.ConfigMigration
import org.spongepowered.configurate.ConfigurationNode

object ClearCompleteConfigPluginMigration : ConfigMigration {
    override fun migrate(node: ConfigurationNode) {
        node.raw(null)
    }
}