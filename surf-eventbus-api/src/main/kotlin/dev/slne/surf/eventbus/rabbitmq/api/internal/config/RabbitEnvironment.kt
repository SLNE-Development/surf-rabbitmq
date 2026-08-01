package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.api.core.environment.requireIn
import dev.slne.surf.eventbus.InternalEventBusApi

/**
 * The environment layer of the RabbitMQ configuration.
 *
 * Replaces the hand-rolled `RabbitMQEnvironmentVariables` plus `EnvironmentOverrideRabbitMQConfig`
 * with surf-api-core's resolver, which brings conversion, range validation and — for the
 * password — keeping the value out of failure messages.
 */
@InternalEventBusApi
object RabbitEnvironment {

    private const val PREFIX = "SURF_EVENTBUS_RABBITMQ_"

    fun resolve(
        environment: EnvironmentVariables,
        fallback: CommonRabbitMQConfig
    ): CommonRabbitMQConfig = object : CommonRabbitMQConfig {

        override fun getHost(): String =
            environment.optional(PREFIX + "HOST") { require("expected a non-blank host") { it.isNotBlank() } }
                ?: fallback.getHost()

        override fun getPort(): Int =
            environment.optionalInt(PREFIX + "PORT") { requireIn(1..65535) } ?: fallback.getPort()

        override fun getUsername(): String =
            environment.optional(PREFIX + "USERNAME") { require("expected a non-blank username") { it.isNotBlank() } }
                ?: fallback.getUsername()

        // Blank is allowed: a broker without authentication is a legitimate local setup.
        override fun getPassword(): String =
            environment.optional(PREFIX + "PASSWORD", sensitive = true) ?: fallback.getPassword()

        override fun getVhost(): String =
            environment.optional(PREFIX + "VHOST") { require("expected a non-blank vhost") { it.isNotBlank() } }
                ?: fallback.getVhost()

        override fun getTimeout(): Int =
            environment.optionalInt(PREFIX + "TIMEOUT") { require("expected a positive timeout") { it > 0 } }
                ?: fallback.getTimeout()

        override fun getRequestTimeoutSeconds(): Int =
            environment.optionalInt(PREFIX + "REQUEST_TIMEOUT_SECONDS") { require("expected a positive timeout") { it > 0 } }
                ?: fallback.getRequestTimeoutSeconds()

        override fun getPublisherPoolSize(): Int =
            environment.optionalInt(PREFIX + "PUBLISHER_POOL_SIZE") { require("expected a positive size") { it > 0 } }
                ?: fallback.getPublisherPoolSize()

        override fun getServerPrefetchCount(): Int =
            environment.optionalInt(PREFIX + "SERVER_PREFETCH_COUNT") { requireIn(0..Short.MAX_VALUE.toInt()) }
                ?: fallback.getServerPrefetchCount()

        override fun isPersistRequests(): Boolean =
            environment.optionalBoolean(PREFIX + "PERSIST_REQUESTS") ?: fallback.isPersistRequests()

        override fun isPersistResponses(): Boolean =
            environment.optionalBoolean(PREFIX + "PERSIST_RESPONSES") ?: fallback.isPersistResponses()

        override fun isOutgoingRequestChunkingEnabled(): Boolean =
            environment.optionalBoolean(PREFIX + "OUTGOING_REQUEST_CHUNKING_ENABLED")
                ?: fallback.isOutgoingRequestChunkingEnabled()

        override fun isOutgoingResponseChunkingEnabled(): Boolean =
            environment.optionalBoolean(PREFIX + "OUTGOING_RESPONSE_CHUNKING_ENABLED")
                ?: fallback.isOutgoingResponseChunkingEnabled()

        override fun getAuditServiceName(): String =
            environment.optional("SURF_EVENTBUS_AUDIT_SERVICE") { require("expected a non-blank service name") { it.isNotBlank() } }
                ?: fallback.getAuditServiceName()
    }
}
