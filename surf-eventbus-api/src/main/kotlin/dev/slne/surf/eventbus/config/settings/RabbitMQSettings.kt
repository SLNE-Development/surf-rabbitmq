package dev.slne.surf.eventbus.config.settings

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.EventBusDefaults
import dev.slne.surf.eventbus.credentials.CredentialsConfigurable

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
) : CredentialsConfigurable {
    override fun toString(): String =
        "RabbitMQSettings(host=$host, port=$port, username=$username, password=<redacted>, " +
            "vhost=$vhost, timeout=$timeout, requestTimeoutSeconds=$requestTimeoutSeconds, " +
            "publisherPoolSize=$publisherPoolSize, serverPrefetchCount=$serverPrefetchCount, " +
            "persistRequests=$persistRequests, persistResponses=$persistResponses, " +
            "outgoingRequestChunkingEnabled=$outgoingRequestChunkingEnabled, " +
            "outgoingResponseChunkingEnabled=$outgoingResponseChunkingEnabled, " +
            "auditServiceName=$auditServiceName, retryTtlMillis=$retryTtlMillis)"
}
