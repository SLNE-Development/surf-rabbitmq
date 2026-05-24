package dev.slne.surf.rabbitmq.test

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import org.jetbrains.annotations.MustBeInvokedByOverriders

abstract class RabbitMqTestCommonInstance {
    lateinit var api: RabbitMQApi

    @MustBeInvokedByOverriders
    open suspend fun onLoad() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onEnable() {

    }

    @MustBeInvokedByOverriders
    open suspend fun onDisable() {

    }

    companion object {
        val instance = requiredService<RabbitMqTestCommonInstance>()
        fun get(): RabbitMqTestCommonInstance = instance
    }
}