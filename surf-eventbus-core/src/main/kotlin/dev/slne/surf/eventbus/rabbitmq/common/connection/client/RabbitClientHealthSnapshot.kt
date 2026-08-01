package dev.slne.surf.eventbus.rabbitmq.common.connection.client

data class RabbitClientHealthSnapshot(
    val connectionName: String,
    val connected: Boolean
)