package dev.slne.surf.eventbus.config.settings

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.EventBusConfig

/**
 * What the bus actually runs on, after all four layers have been applied.
 *
 * Distinct from [EventBusConfig], and deliberately so: that one is the *file*, where every
 * field is a sentinel meaning "I have no opinion", and this one is the *answer*, where every
 * field has a value. Reusing one type for both would mean either a file that cannot express
 * "unset" or a runtime that has to re-resolve on every read.
 */
@InternalEventBusApi
data class EventBusSettings(
    val rabbitmq: RabbitMQSettings = RabbitMQSettings(),
    val redis: RedisSettings = RedisSettings(),
)
