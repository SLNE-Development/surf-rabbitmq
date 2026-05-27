package dev.slne.surf.rabbitmq.common

import dev.slne.surf.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
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
            RabbitClient.closeEventLoopGroup()
        }
    }

    companion object {
        fun get(): RabbitMQCommonInstance = RabbitMQInstance.instance as RabbitMQCommonInstance
    }
}