package dev.slne.surf.eventbus.rabbitmq.connection

data class RabbitConnectionSnapshot(
    val status: RabbitConnectionStatus,
    val generation: Long
)