package dev.slne.surf.rabbitmq.common.connection.client

data class RabbitClientHealthSnapshot(
    val connectionName: String,
    val publisherConnected: Boolean,
    val consumerConnected: Boolean,
)