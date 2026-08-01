package dev.slne.surf.eventbus.rabbitmq

import dev.slne.surf.eventbus.rabbitmq.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.MustBeInvokedByOverriders

abstract class RabbitMQCommonInstance : RabbitMQInstance {

    @MustBeInvokedByOverriders
    open suspend fun onLoad() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onEnable() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onDisable() {
        withContext(NonCancellable) {
            RabbitClient.closeSharedResources()
        }
    }

    companion object {
        fun get(): RabbitMQCommonInstance = RabbitMQInstance.instance as RabbitMQCommonInstance
    }
}
