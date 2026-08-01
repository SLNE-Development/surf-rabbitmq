package dev.slne.surf.eventbus.platform

import dev.slne.surf.eventbus.rabbitmq.connection.RabbitClient
import dev.slne.surf.eventbus.redis.RedisRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.MustBeInvokedByOverriders

/**
 * The lifecycle a hosted platform (Paper, Velocity) drives, for **both** transports.
 *
 * The predecessor, `RabbitMQCommonInstance`, closed Rabbit's shared resources and knew nothing
 * about Redis — because Redis had its own SPI that Paper and Velocity never implemented. One
 * instance per platform now means one place that starts and stops both.
 */
abstract class EventBusPlatformInstance : EventBusInstance {

    @MustBeInvokedByOverriders
    open suspend fun onLoad() {
        RedisRuntime.instance.load()
    }

    @MustBeInvokedByOverriders
    open suspend fun onEnable() {
    }

    @MustBeInvokedByOverriders
    open suspend fun onDisable() {
        withContext(NonCancellable) {
            RabbitClient.closeSharedResources()
            RedisRuntime.instance.disable()
        }
    }

    companion object {
        fun get(): EventBusPlatformInstance = EventBusInstance.instance as EventBusPlatformInstance
    }
}
