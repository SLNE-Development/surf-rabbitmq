package dev.slne.surf.eventbus.rabbitmq.core.event

import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.eventbus.rabbitmq.api.event.SubscriptionMode
import java.lang.reflect.Method

/** One `@RabbitSubscribe` method and the binding it requires. */
data class EventSubscription(
    val eventClass: Class<out RabbitEventPacket>,
    val pattern: String,
    val mode: SubscriptionMode,
    val retry: Boolean,
    val listener: Any,
    val method: Method
)
