package dev.slne.surf.rabbitmq.test

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import org.jetbrains.annotations.MustBeInvokedByOverriders

abstract class RabbitMqTestCommonInstance {
    lateinit var api: SurfRabbitApi

    @MustBeInvokedByOverriders
    open suspend fun onLoad() = Unit

    @MustBeInvokedByOverriders
    open suspend fun onEnable() = Unit

    @MustBeInvokedByOverriders
    open suspend fun onDisable() = Unit

    companion object {
        val instance = requiredService<RabbitMqTestCommonInstance>()
        fun get(): RabbitMqTestCommonInstance = instance
    }
}
