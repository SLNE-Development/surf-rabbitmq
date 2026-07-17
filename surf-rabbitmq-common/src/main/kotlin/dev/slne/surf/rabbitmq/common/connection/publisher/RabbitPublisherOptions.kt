package dev.slne.surf.rabbitmq.common.connection.publisher

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class RabbitPublisherOptions(
    val confirmPublishes: Boolean = true,
    val confirmTimeoutMillis: Long = 5_000,
    val maxAttempts: Int = 3,
    val retryDelay: Duration = 250.milliseconds
) {
    init {
        require(!confirmPublishes || confirmTimeoutMillis > 0) {
            "Publisher confirm timeout must be greater than 0 when confirms are enabled"
        }
        require(maxAttempts in 1..100) { "Publisher max attempts must be in 1..100" }
        require(retryDelay.isPositive()) { "Publisher retry delay must not be negative" }
    }
}
