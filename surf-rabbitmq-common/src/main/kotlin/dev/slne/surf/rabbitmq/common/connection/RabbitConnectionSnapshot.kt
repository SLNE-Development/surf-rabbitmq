package dev.slne.surf.rabbitmq.common.connection

data class RabbitConnectionSnapshot(
    val status: RabbitConnectionStatus,
    val generation: Long
)