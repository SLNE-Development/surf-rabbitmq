package dev.slne.surf.rabbitmq.velocity

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.internal.Platform
import dev.slne.surf.rabbitmq.api.internal.PlatformDependent
import dev.slne.surf.rabbitmq.velocity.reflection.JavaPluginLoaderProxy
import dev.slne.surf.rabbitmq.velocity.reflection.SerializedPluginDescriptionProxy
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.toPath
import kotlin.jvm.optionals.getOrNull

@AutoService(PlatformDependent::class)
class VelocityPlatformDependentImpl : PlatformDependent {
    override val platform: Platform = Platform.VELOCITY

    private val velocityPluginLoader by lazy {
        JavaPluginLoaderProxy.get().createInstance(plugin.proxy, Path("plugins"))
    }

    override fun getDataPathFromCallingPlugin(clazz: Class<*>): Path {
        val jarLocation = clazz.protectionDomain.codeSource.location.toURI().toPath()
        val pluginName = getPluginNameFromCallingPlugin(clazz)
        return jarLocation / pluginName
    }

    private fun getPluginNameFromCallingPlugin(clazz: Class<*>): String {
        val jarLocation = clazz.protectionDomain.codeSource.location.toURI().toPath()
        val pluginInfo = JavaPluginLoaderProxy.get()
            .getSerializedPluginInfo(velocityPluginLoader, jarLocation)
            .getOrNull()

        if (pluginInfo == null) {
            error("Could not find plugin info for class ${clazz.name}")
        }

        val id = SerializedPluginDescriptionProxy.get().getId(pluginInfo)
        return id
    }
}