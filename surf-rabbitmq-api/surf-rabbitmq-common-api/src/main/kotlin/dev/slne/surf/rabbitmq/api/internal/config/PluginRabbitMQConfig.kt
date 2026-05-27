package dev.slne.surf.rabbitmq.api.internal.config

import dev.slne.surf.api.core.config.constraints.MaxNumber
import dev.slne.surf.api.core.config.constraints.MinNumber
import dev.slne.surf.api.core.config.constraints.PositiveNumber
import dev.slne.surf.api.core.config.constraints.Trimmed
import dev.slne.surf.api.core.config.createSpongeYmlConfigManager
import dev.slne.surf.api.core.config.migration.ConfigMigrationBuilder
import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.api.core.config.type.BooleanOrDefault
import dev.slne.surf.api.core.config.type.StringOrDefault
import dev.slne.surf.api.core.config.type.number.IntOr
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.internal.config.migration.plugin.ClearCompleteConfigPluginMigration
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.file.Path

@ConfigSerializable
@InternalRabbitMQ
data class PluginRabbitMQConfig(
    @field:Comment("RabbitMQ server hostname or IP address.")
    @Trimmed
    @JvmField
    val host: StringOrDefault = StringOrDefault.USE_DEFAULT,

    @field:Comment("RabbitMQ server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    @JvmField
    val port: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment("RabbitMQ username used for authentication.")
    @Trimmed
    @JvmField
    val username: StringOrDefault = StringOrDefault.USE_DEFAULT,

    @field:Comment("RabbitMQ password used for authentication.")
    @JvmField
    val password: StringOrDefault = StringOrDefault.USE_DEFAULT,

    @field:Comment("RabbitMQ virtual host. The default virtual host is `/`.")
    @Trimmed
    @JvmField
    val vhost: StringOrDefault = StringOrDefault.USE_DEFAULT,

    @field:Comment(
        """
    Connection timeout in seconds.
    
    This controls how long the RabbitMQ client waits while establishing the
    initial connection.
    """
    )
    @JvmField
    @PositiveNumber
    val timeout: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment(
        """
    Request timeout in seconds.
    
    Requests that do not receive a response within this time are completed
    with a timeout exception. The same value is also used as the expiration
    for queued request messages.
    """
    )
    @JvmField
    @PositiveNumber
    val requestTimeoutSeconds: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment(
        """
    Number of dedicated publisher workers used for sending messages.
    
    Each publisher owns its own RabbitMQ channel and serializes publish operations
    on that channel. Increasing this value can improve throughput for many
    concurrent requests, but also opens more channels on the RabbitMQ connection.
    
    A small value is usually enough because a few fast publishers can already
    saturate the broker or network.
    """
    )
    @PositiveNumber
    @JvmField
    val publisherPoolSize: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment(
        """
    Maximum number of request messages the server may receive without
    acknowledging them. Only applies to microservices.
        
    A value of `0` disables the prefetch limit.
    """
    )
    @MinNumber(0.0)
    @MaxNumber(Short.MAX_VALUE.toDouble())
    @JvmField
    val serverPrefetchCount: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment(
        """
    Whether request messages should be published as persistent RabbitMQ
    messages.
        
    Persistent requests survive broker restarts when they are routed to a
    durable queue.
    """
    )
    @JvmField
    val persistRequests: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,

    @field:Comment(
        """
    Whether response messages should be published as persistent RabbitMQ
    messages.
        
    Responses are usually transient because they are sent to temporary
    callback queues.
    """
    )
    @JvmField
    val persistResponses: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,

    @field:Comment(
        "Enables publishing large request packets as multiple RabbitMQ messages.\n\nKeep this " +
                "disabled during mixed-version rollouts. Old servers cannot understand chunked request messages."
    )
    @JvmField
    val outgoingRequestChunkingEnabled: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,

    @field:Comment(
        "Enables publishing large response packets as multiple RabbitMQ messages.\n\n" +
                "This can usually be enabled during mixed-version rollouts because responses are only chunked when" +
                " the requesting client explicitly advertises support for chunked responses."
    )
    @JvmField
    val outgoingResponseChunkingEnabled: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
) : CommonRabbitMQConfig {

    override fun getHost(): String = host or GlobalRabbitMQConfig.getConfig().getHost()
    override fun getPort(): Int = port or GlobalRabbitMQConfig.getConfig().getPort()
    override fun getUsername(): String = username or GlobalRabbitMQConfig.getConfig().getUsername()
    override fun getPassword(): String = password or GlobalRabbitMQConfig.getConfig().getPassword()
    override fun getVhost(): String = vhost or GlobalRabbitMQConfig.getConfig().getVhost()
    override fun getTimeout(): Int = timeout or GlobalRabbitMQConfig.getConfig().getTimeout()
    override fun getRequestTimeoutSeconds(): Int =
        requestTimeoutSeconds or GlobalRabbitMQConfig.getConfig().getRequestTimeoutSeconds()

    override fun getPublisherPoolSize(): Int =
        publisherPoolSize or GlobalRabbitMQConfig.getConfig().getPublisherPoolSize()

    override fun getServerPrefetchCount(): Int =
        serverPrefetchCount or GlobalRabbitMQConfig.getConfig().getServerPrefetchCount()

    override fun isPersistRequests(): Boolean = persistRequests or GlobalRabbitMQConfig.getConfig().isPersistRequests()
    override fun isPersistResponses(): Boolean =
        persistResponses or GlobalRabbitMQConfig.getConfig().isPersistResponses()

    override fun isOutgoingRequestChunkingEnabled(): Boolean {
        return outgoingRequestChunkingEnabled or GlobalRabbitMQConfig.getConfig().isOutgoingRequestChunkingEnabled()
    }

    override fun isOutgoingResponseChunkingEnabled(): Boolean {
        return outgoingResponseChunkingEnabled or GlobalRabbitMQConfig.getConfig().isOutgoingResponseChunkingEnabled()
    }

    @InternalRabbitMQ
    companion object {
        fun create(path: Path): PluginRabbitMQConfig {
            val manager = surfConfigApi.createSpongeYmlConfigManager<PluginRabbitMQConfig>(
                configFolder = path,
                configFileName = "rabbitmq.yml",
                migrations = ConfigMigrationBuilder()
                    .migration(1, ClearCompleteConfigPluginMigration)
            )

            return manager.config
        }
    }
}