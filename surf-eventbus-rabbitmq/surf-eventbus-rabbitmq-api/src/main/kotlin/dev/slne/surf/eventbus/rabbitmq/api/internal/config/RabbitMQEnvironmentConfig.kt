package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ

internal object RabbitMQEnvironmentVariables {
    const val HOST = "SURF_RABBITMQ_HOST"
    const val PORT = "SURF_RABBITMQ_PORT"
    const val USERNAME = "SURF_RABBITMQ_USERNAME"
    const val PASSWORD = "SURF_RABBITMQ_PASSWORD"
    const val VHOST = "SURF_RABBITMQ_VHOST"
    const val TIMEOUT = "SURF_RABBITMQ_TIMEOUT"
    const val REQUEST_TIMEOUT_SECONDS = "SURF_RABBITMQ_REQUEST_TIMEOUT_SECONDS"
    const val PUBLISHER_POOL_SIZE = "SURF_RABBITMQ_PUBLISHER_POOL_SIZE"
    const val SERVER_PREFETCH_COUNT = "SURF_RABBITMQ_SERVER_PREFETCH_COUNT"
    const val PERSIST_REQUESTS = "SURF_RABBITMQ_PERSIST_REQUESTS"
    const val PERSIST_RESPONSES = "SURF_RABBITMQ_PERSIST_RESPONSES"
    const val OUTGOING_REQUEST_CHUNKING_ENABLED =
        "SURF_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED"
    const val OUTGOING_RESPONSE_CHUNKING_ENABLED =
        "SURF_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED"
}

internal fun interface EnvironmentVariableLookup {
    operator fun get(name: String): String?
}

private object ProcessEnvironmentVariableLookup : EnvironmentVariableLookup {
    override fun get(name: String): String? = System.getenv(name)
}

/** Resolves YAML, legacy system-property, and process-environment configuration layers. */
@InternalRabbitMQ
fun resolveRabbitMQConfig(
    global: GlobalRabbitMQConfig,
    plugin: PluginRabbitMQConfig? = null,
): CommonRabbitMQConfig = resolveRabbitMQConfig(
    global = global,
    plugin = plugin,
    environment = ProcessEnvironmentVariableLookup,
)

internal fun resolveRabbitMQConfig(
    global: GlobalRabbitMQConfig,
    plugin: PluginRabbitMQConfig? = null,
    environment: EnvironmentVariableLookup,
): CommonRabbitMQConfig {
    val yamlConfig = if (plugin == null) global else PluginWithGlobalFallback(plugin, global)
    return EnvironmentOverrideRabbitMQConfig(yamlConfig, environment)
}

private class PluginWithGlobalFallback(
    private val plugin: PluginRabbitMQConfig,
    private val global: CommonRabbitMQConfig,
) : CommonRabbitMQConfig {
    override fun getHost(): String = plugin.host or global.getHost()
    override fun getPort(): Int = plugin.port or global.getPort()
    override fun getUsername(): String = plugin.username or global.getUsername()
    override fun getPassword(): String = plugin.password or global.getPassword()
    override fun getVhost(): String = plugin.vhost or global.getVhost()
    override fun getTimeout(): Int = plugin.timeout or global.getTimeout()
    override fun getRequestTimeoutSeconds(): Int =
        plugin.requestTimeoutSeconds or global.getRequestTimeoutSeconds()

    override fun getPublisherPoolSize(): Int = plugin.publisherPoolSize or global.getPublisherPoolSize()
    override fun getServerPrefetchCount(): Int =
        plugin.serverPrefetchCount or global.getServerPrefetchCount()

    override fun isPersistRequests(): Boolean = plugin.persistRequests or global.isPersistRequests()
    override fun isPersistResponses(): Boolean = plugin.persistResponses or global.isPersistResponses()
    override fun isOutgoingRequestChunkingEnabled(): Boolean =
        plugin.outgoingRequestChunkingEnabled or global.isOutgoingRequestChunkingEnabled()

    override fun isOutgoingResponseChunkingEnabled(): Boolean =
        plugin.outgoingResponseChunkingEnabled or global.isOutgoingResponseChunkingEnabled()
}

private class EnvironmentOverrideRabbitMQConfig(
    private val fallback: CommonRabbitMQConfig,
    private val environment: EnvironmentVariableLookup,
) : CommonRabbitMQConfig {
    override fun getHost(): String = text(RabbitMQEnvironmentVariables.HOST, fallback::getHost)
    override fun getPort(): Int = integer(
        RabbitMQEnvironmentVariables.PORT,
        1..65535,
        fallback::getPort,
    )

    override fun getUsername(): String = text(RabbitMQEnvironmentVariables.USERNAME, fallback::getUsername)
    override fun getPassword(): String =
        text(RabbitMQEnvironmentVariables.PASSWORD, fallback::getPassword, allowBlank = true)

    override fun getVhost(): String = text(RabbitMQEnvironmentVariables.VHOST, fallback::getVhost)
    override fun getTimeout(): Int = positiveInteger(
        RabbitMQEnvironmentVariables.TIMEOUT,
        fallback::getTimeout,
    )

    override fun getRequestTimeoutSeconds(): Int = positiveInteger(
        RabbitMQEnvironmentVariables.REQUEST_TIMEOUT_SECONDS,
        fallback::getRequestTimeoutSeconds,
    )

    override fun getPublisherPoolSize(): Int = positiveInteger(
        RabbitMQEnvironmentVariables.PUBLISHER_POOL_SIZE,
        fallback::getPublisherPoolSize,
    )

    override fun getServerPrefetchCount(): Int = integer(
        RabbitMQEnvironmentVariables.SERVER_PREFETCH_COUNT,
        0..Short.MAX_VALUE,
        fallback::getServerPrefetchCount,
    )

    override fun isPersistRequests(): Boolean = boolean(
        RabbitMQEnvironmentVariables.PERSIST_REQUESTS,
        fallback::isPersistRequests,
    )

    override fun isPersistResponses(): Boolean = boolean(
        RabbitMQEnvironmentVariables.PERSIST_RESPONSES,
        fallback::isPersistResponses,
    )

    override fun isOutgoingRequestChunkingEnabled(): Boolean = boolean(
        RabbitMQEnvironmentVariables.OUTGOING_REQUEST_CHUNKING_ENABLED,
        fallback::isOutgoingRequestChunkingEnabled,
    )

    override fun isOutgoingResponseChunkingEnabled(): Boolean = boolean(
        RabbitMQEnvironmentVariables.OUTGOING_RESPONSE_CHUNKING_ENABLED,
        fallback::isOutgoingResponseChunkingEnabled,
    )

    private fun text(name: String, fallback: () -> String, allowBlank: Boolean = false): String {
        val value = environment[name] ?: return fallback()
        require(allowBlank || value.isNotBlank()) {
            "Environment variable $name must not be blank."
        }
        return value
    }

    private fun positiveInteger(name: String, fallback: () -> Int): Int =
        integer(name, 1..Int.MAX_VALUE, fallback)

    private fun integer(name: String, range: IntRange, fallback: () -> Int): Int {
        val rawValue = environment[name] ?: return fallback()
        val value = rawValue.toIntOrNull()
            ?: throw IllegalArgumentException("Environment variable $name must be an integer.")
        require(value in range) {
            "Environment variable $name must be in the range ${range.first}..${range.last}."
        }
        return value
    }

    private fun boolean(name: String, fallback: () -> Boolean): Boolean {
        val rawValue = environment[name] ?: return fallback()
        return when {
            rawValue.equals("true", ignoreCase = true) -> true
            rawValue.equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException(
                "Environment variable $name must be either 'true' or 'false'."
            )
        }
    }
}
