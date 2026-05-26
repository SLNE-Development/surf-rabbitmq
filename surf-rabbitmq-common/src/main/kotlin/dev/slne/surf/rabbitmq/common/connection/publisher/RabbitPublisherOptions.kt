package dev.slne.surf.rabbitmq.common.connection.publisher

data class RabbitPublisherOptions(
    val confirmPublishes: Boolean = true,
    val confirmTimeoutMillis: Long = 5_000,
    val maxAttempts: Int = 3,
    val retryDelayMillis: Long = 250
)