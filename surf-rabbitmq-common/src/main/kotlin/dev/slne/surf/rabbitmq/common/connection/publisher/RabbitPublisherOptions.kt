package dev.slne.surf.rabbitmq.common.connection.publisher

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class RabbitPublisherOptions(
    val confirmPublishes: Boolean = true,
    val confirmTimeout: Duration = 5.seconds,
    val recoveryTimeout: Duration = 45.seconds,
    val operationTimeout: Duration = 15.seconds,
    val channelOpenAttempts: Int = 3,
    val channelRetryDelay: Duration = 500.milliseconds
)