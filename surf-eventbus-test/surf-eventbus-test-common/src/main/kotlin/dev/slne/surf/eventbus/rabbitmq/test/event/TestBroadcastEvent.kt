package dev.slne.surf.eventbus.rabbitmq.test.event

import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEventPacket
import kotlinx.serialization.Serializable

@Serializable
@RabbitEvent("test.broadcast")
class TestBroadcastEvent(val message: String) : RabbitEventPacket()
