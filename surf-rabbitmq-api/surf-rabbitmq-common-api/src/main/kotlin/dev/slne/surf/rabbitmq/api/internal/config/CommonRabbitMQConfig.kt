package dev.slne.surf.rabbitmq.api.internal.config

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

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
}