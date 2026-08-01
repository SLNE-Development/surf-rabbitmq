package dev.slne.surf.eventbus.config

import dev.slne.surf.eventbus.InternalEventBusApi
import java.util.UUID

/**
 * What the bus actually runs on, after all four layers have been applied.
 *
 * Distinct from [EventBusConfig], and deliberately so: that one is the *file*, where every
 * field is a sentinel meaning "I have no opinion", and this one is the *answer*, where every
 * field has a value. Reusing one type for both would mean either a file that cannot express
 * "unset" or a runtime that has to re-resolve on every read.
 */
@InternalEventBusApi
data class EventBusSettings(
    val rabbitmq: RabbitMQSettings = RabbitMQSettings(),
    val redis: RedisSettings = RedisSettings(),
)

/**
 * The RabbitMQ settings the connection is opened with.
 *
 * A data class rather than the old `CommonRabbitMQConfig` interface with `getHost()` /
 * `isPersistRequests()`: the bean shape was a leftover from Java consumers, and it forced every
 * test that wanted to change one value to write out all thirteen members by hand.
 */
@InternalEventBusApi
data class RabbitMQSettings(
    val host: String = EventBusDefaults.RABBIT_HOST,
    val port: Int = EventBusDefaults.RABBIT_PORT,
    val username: String = EventBusDefaults.RABBIT_USERNAME,
    val password: String = EventBusDefaults.RABBIT_PASSWORD,
    val vhost: String = EventBusDefaults.RABBIT_VHOST,
    val timeout: Int = EventBusDefaults.TIMEOUT_SECONDS,
    val requestTimeoutSeconds: Int = EventBusDefaults.REQUEST_TIMEOUT_SECONDS,
    val publisherPoolSize: Int = EventBusDefaults.PUBLISHER_POOL_SIZE,
    val serverPrefetchCount: Int = EventBusDefaults.SERVER_PREFETCH_COUNT,
    val persistRequests: Boolean = EventBusDefaults.PERSIST_REQUESTS,
    val persistResponses: Boolean = EventBusDefaults.PERSIST_RESPONSES,
    val outgoingRequestChunkingEnabled: Boolean = EventBusDefaults.OUTGOING_REQUEST_CHUNKING_ENABLED,
    val outgoingResponseChunkingEnabled: Boolean = EventBusDefaults.OUTGOING_RESPONSE_CHUNKING_ENABLED,
    val auditServiceName: String = EventBusDefaults.AUDIT_SERVICE_NAME,

    /**
     * TTL per retry tier in milliseconds, index-aligned with `RetryTier.entries`.
     *
     * Not configurable from yaml: queue arguments are part of a queue's identity, so every
     * process sharing a broker must agree on these values or the redeclaration fails. Only
     * tests override it, to shrink the ladder to sub-second values.
     */
    val retryTtlMillis: List<Long> = EventBusDefaults.RETRY_TTL_MILLIS,
) {
    override fun toString(): String =
        "RabbitMQSettings(host=$host, port=$port, username=$username, password=<redacted>, " +
                "vhost=$vhost, timeout=$timeout, requestTimeoutSeconds=$requestTimeoutSeconds, " +
                "publisherPoolSize=$publisherPoolSize, serverPrefetchCount=$serverPrefetchCount, " +
                "persistRequests=$persistRequests, persistResponses=$persistResponses, " +
                "outgoingRequestChunkingEnabled=$outgoingRequestChunkingEnabled, " +
                "outgoingResponseChunkingEnabled=$outgoingResponseChunkingEnabled, " +
                "auditServiceName=$auditServiceName, retryTtlMillis=$retryTtlMillis)"
}

/** The Redis settings the client connects with. */
@InternalEventBusApi
data class RedisSettings(
    val host: String = EventBusDefaults.REDIS_HOST,
    val port: Int = EventBusDefaults.REDIS_PORT,
    val password: String? = null,
    val clientName: String = EventBusDefaults.redisClientName(),
) {
    override fun toString(): String =
        "RedisSettings(host=$host, port=$port, password=<redacted>, clientName=$clientName)"
}

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
