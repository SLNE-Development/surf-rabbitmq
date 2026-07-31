package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
interface CommonRabbitMQConfig {
    fun getHost(): String
    fun getPort(): Int
    fun getUsername(): String
    fun getPassword(): String
    fun getVhost(): String
    fun getTimeout(): Int
    fun getRequestTimeoutSeconds(): Int
    fun getPublisherPoolSize(): Int
    fun getServerPrefetchCount(): Int
    fun isPersistRequests(): Boolean
    fun isPersistResponses(): Boolean
    fun isOutgoingRequestChunkingEnabled(): Boolean
    fun isOutgoingResponseChunkingEnabled(): Boolean

    /**
     * TTL per retry tier in milliseconds, index-aligned with `RetryTier.entries`.
     *
     * A default member rather than an abstract one: only test configs override it, to
     * shrink the ladder to sub-second values. Queue arguments are part of a queue's
     * identity, so all processes sharing a broker must agree on these values.
     */
    fun getRetryTtlMillis(): List<Long> = listOf(10_000L, 60_000L, 300_000L)

    /**
     * The service name the audit reports are sent to.
     *
     * A default member because only the environment and yaml layers ever override it; a test
     * config with no opinion on auditing should not need to implement it.
     */
    fun getAuditServiceName(): String = "surf-eventbus-audit"
}