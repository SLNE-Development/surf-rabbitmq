package dev.slne.surf.eventbus.config

import dev.slne.surf.eventbus.InternalEventBusApi
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

/**
 * One file for the whole bus, in two sections.
 *
 * There were four config classes across two packages: `GlobalRabbitMQConfig` and
 * `PluginRabbitMQConfig` held the same fourteen fields with the same KDoc, and `RedisConfig`
 * held four more in another module entirely. An operator had to know which file a setting lived
 * in before they could change it, and the two transports disagreed on how many layers there
 * were.
 *
 * Every field is a sentinel (`USE_DEFAULT`), which is what makes the layering work field by
 * field rather than file by file: an empty section in the plugin file overrides nothing, and a
 * single key in it overrides exactly that key. The built-in defaults live once, in
 * [EventBusDefaults].
 *
 * The same class is both the global and the plugin file. They differ in *which* file they are
 * read from, not in shape — which is why one class can express both and two could not.
 */
@ConfigSerializable
@InternalEventBusApi
data class EventBusConfig(
    @field:Comment("How this process reaches RabbitMQ, which carries rpc(...) calls.")
    val rabbitmq: EventBusRabbitMQConfig = EventBusRabbitMQConfig(),
    @field:Comment("How this process reaches Redis, which carries publish(...) and query(...).")
    val redis: EventBusRedisConfig = EventBusRedisConfig(),
)
