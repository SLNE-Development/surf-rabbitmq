package dev.slne.surf.rabbitmq.paper

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.internal.PlatformDependent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

@AutoService(PlatformDependent::class)
class PaperPlatformDependentImpl : PlatformDependent {
    override fun getDataPathFromCallingPlugin(clazz: Class<*>): Path {
        val plugin = JavaPlugin.getProvidingPlugin(clazz)
        return plugin.dataPath
    }
}