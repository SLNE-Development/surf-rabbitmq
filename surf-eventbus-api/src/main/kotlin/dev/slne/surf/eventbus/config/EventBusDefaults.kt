package dev.slne.surf.eventbus.config

import dev.slne.surf.eventbus.InternalEventBusApi
import java.util.*

/**
 * The built-in defaults, at one place.
 *
 * They used to sit in `GlobalRabbitMQConfig`'s property initialisers *and* in its
 * `getX() = field or <default>` bodies — two literals per field that nothing kept in step.
 */
@InternalEventBusApi
object EventBusDefaults {
    const val RABBIT_HOST = "localhost"
    const val RABBIT_PORT = 5672
    const val RABBIT_USERNAME = "guest"
    const val RABBIT_PASSWORD = "guest"
    const val RABBIT_VHOST = "/"
    const val TIMEOUT_SECONDS = 30
    const val REQUEST_TIMEOUT_SECONDS = 60
    const val PUBLISHER_POOL_SIZE = 2
    const val SERVER_PREFETCH_COUNT = 128
    const val PERSIST_REQUESTS = true
    const val PERSIST_RESPONSES = false
    const val OUTGOING_REQUEST_CHUNKING_ENABLED = false
    const val OUTGOING_RESPONSE_CHUNKING_ENABLED = true
    const val AUDIT_SERVICE_NAME = "surf-eventbus-audit"

    const val REDIS_HOST = "localhost"
    const val REDIS_PORT = 6379

    val RETRY_TTL_MILLIS = listOf(10_000L, 60_000L, 300_000L)

    fun redisClientName(): String = "surf-eventbus-client-${UUID.randomUUID()}"
}
