package dev.slne.surf.rabbitmq.common.testing

import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig

/** Points a [CommonRabbitMQConfig] at the Testcontainers broker with test-sized timeouts. */
fun testConfig(
    requestTimeoutSeconds: Int = 10,
    prefetch: Int = 16,
    requestChunking: Boolean = false,
    responseChunking: Boolean = true
): CommonRabbitMQConfig = object : CommonRabbitMQConfig {
    private val factory = RabbitBrokerExtension.connectionFactory()

    override fun getHost() = factory.host
    override fun getPort() = factory.port
    override fun getUsername() = factory.username
    override fun getPassword() = factory.password
    override fun getVhost() = factory.virtualHost
    override fun getTimeout() = 10
    override fun getRequestTimeoutSeconds() = requestTimeoutSeconds
    override fun getPublisherPoolSize() = 2
    override fun getServerPrefetchCount() = prefetch
    override fun isPersistRequests() = true
    override fun isPersistResponses() = false
    override fun isOutgoingRequestChunkingEnabled() = requestChunking
    override fun isOutgoingResponseChunkingEnabled() = responseChunking
    override fun getRetryTtlMillis() = listOf(500L, 1_000L, 1_500L)
}
