package dev.slne.surf.rabbitmq.common

import com.google.auto.service.AutoService
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kyori.adventure.util.Services
import org.jetbrains.annotations.MustBeInvokedByOverriders

abstract class RabbitMQInstance {

    @MustBeInvokedByOverriders
    open suspend fun onLoad() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onEnable() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onDisable() {
        withContext(Dispatchers.IO) {
            RabbitClient.closeEventLoopGroup()
        }
    }

    companion object {
        val instance = requiredService<RabbitMQInstance>()
        fun get(): RabbitMQInstance = instance

        @AutoService(RabbitMQInstance::class)
        class Default : RabbitMQInstance(), Services.Fallback
    }
}