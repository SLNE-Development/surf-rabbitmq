package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.surfapi.core.api.util.requiredService
import java.nio.file.Path

@InternalRabbitMQ
interface PlatformDependent {

    fun getDataPathFromCallingPlugin(clazz: Class<*>): Path

    val platform: Platform

    companion object {
        val instance = requiredService<PlatformDependent>()
    }
}