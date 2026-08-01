package dev.slne.surf.eventbus.rabbitmq.connection

data class RabbitClientHealthSnapshot(
    val connectionName: String,
    val connected: Boolean
)