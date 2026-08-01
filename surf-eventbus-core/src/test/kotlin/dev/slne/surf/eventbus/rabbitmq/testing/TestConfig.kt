package dev.slne.surf.eventbus.rabbitmq.testing

import dev.slne.surf.eventbus.config.RabbitMQSettings

/** Points a [RabbitMQSettings] at the Testcontainers broker with test-sized timeouts. */
fun testConfig(
    requestTimeoutSeconds: Int = 10,
    prefetch: Int = 16,
    requestChunking: Boolean = false,
    responseChunking: Boolean = true
): RabbitMQSettings {
    val factory = RabbitBrokerExtension.connectionFactory()

    return RabbitMQSettings(
        host = factory.host,
        port = factory.port,
        username = factory.username,
        password = factory.password,
        vhost = factory.virtualHost,
        timeout = 10,
        requestTimeoutSeconds = requestTimeoutSeconds,
        publisherPoolSize = 2,
        serverPrefetchCount = prefetch,
        persistRequests = true,
        persistResponses = false,
        outgoingRequestChunkingEnabled = requestChunking,
        outgoingResponseChunkingEnabled = responseChunking,
        // Sub-second tiers keep the retry ladder testable; the real one spans five minutes.
        retryTtlMillis = listOf(500L, 1_000L, 1_500L),
    )
}
