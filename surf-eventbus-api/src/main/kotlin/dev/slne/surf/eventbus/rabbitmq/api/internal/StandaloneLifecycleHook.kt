package dev.slne.surf.eventbus.rabbitmq.api.internal

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import java.nio.file.Path

@InternalEventBusApi
interface StandaloneLifecycleHook {
    fun onInit(dataPath: Path)
    suspend fun beforeConnect()
    suspend fun afterDisconnect()

    @InternalEventBusApi
    companion object : StandaloneLifecycleHook by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<StandaloneLifecycleHook>()
