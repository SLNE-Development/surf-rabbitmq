package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ

/** Resolves YAML and process-environment configuration layers. */
@InternalRabbitMQ
fun resolveRabbitMQConfig(
    global: GlobalRabbitMQConfig,
    plugin: PluginRabbitMQConfig? = null,
    environment: EnvironmentVariables = EnvironmentVariables.system,
): CommonRabbitMQConfig {
    val yamlConfig = if (plugin == null) global else PluginWithGlobalFallback(plugin, global)
    return RabbitEnvironment.resolve(environment, yamlConfig)
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
