package dev.slne.surf.eventbus.rabbitmq.common.connection

data class RabbitConnectionSnapshot(
    val status: RabbitConnectionStatus,
    val generation: Long
)