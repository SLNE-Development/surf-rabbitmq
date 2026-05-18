package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.api.core.config.constraints.MaxNumber
import dev.slne.surf.api.core.config.constraints.MinNumber
import dev.slne.surf.api.core.config.constraints.PositiveNumber
import dev.slne.surf.api.core.config.constraints.Trimmed
import dev.slne.surf.api.core.config.createSpongeYmlConfig
import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.api.core.config.type.BooleanOrDefault
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import org.apache.commons.lang3.BooleanUtils
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@ConfigSerializable
@InternalRabbitMQ
data class RabbitMQConfig(
    @field:Comment("RabbitMQ server hostname or IP address.")
    @Trimmed
    val host: String = "localhost",

    @field:Comment("RabbitMQ server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    val port: Int = 5672,

    @field:Comment("RabbitMQ username used for authentication.")
    @Trimmed
    val username: String = "guest",

    @field:Comment("RabbitMQ password used for authentication.")
    val password: String = "guest",

    @field:Comment("RabbitMQ virtual host. The default virtual host is `/`.")
    @Trimmed
    val vhost: String = "/",

    @field:Comment(
        """
        Connection timeout in seconds.
        
        This controls how long the RabbitMQ client waits while establishing the
        initial connection.
    """
    )
    @PositiveNumber
    val timeout: Long = 30.seconds.inWholeSeconds,

    @field:Comment(
        """
        Request timeout in seconds.
        
        Requests that do not receive a response within this time are completed
        with a timeout exception. The same value is also used as the expiration
        for queued request messages.
    """
    )
    @PositiveNumber
    val requestTimeoutSeconds: Long = 60.seconds.inWholeSeconds,

    @field:Comment(
        """
        Maximum number of request messages the server may receive without
        acknowledging them.
        
        A value of `0` disables the prefetch limit.
    """
    )
    @MinNumber(0.0)
    @MaxNumber(Short.MAX_VALUE.toDouble())
    val serverPrefetchCount: Short = 128,

    @field:Comment(
        """
        Whether request messages should be published as persistent RabbitMQ
        messages.
        
        Persistent requests survive broker restarts when they are routed to a
        durable queue.
    """
    )
    val persistRequests: Boolean = true,

    @field:Comment(
        """
        Whether response messages should be published as persistent RabbitMQ
        messages.
        
        Responses are usually transient because they are sent to temporary
        callback queues.
    """
    )
    val persistResponses: Boolean = false,

    @field:Comment(
        """
        Enables publishing large outgoing packets as multiple RabbitMQ messages.
        
        Keep this disabled during mixed-version rollouts. New servers can read both
        legacy and chunked packets, but old servers cannot understand chunked request
        messages.
        
        The default setting can be overridden by setting the system property
        `surf.rabbitmq.outgoingPacketChunkingEnabled` to `true` or `false`.
    """
    )
    val outgoingPacketChunkingEnabled: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
) {

    fun isOutgoingPacketChunkingEnabled(): Boolean {
        return outgoingPacketChunkingEnabled or systemChunkingEnabled
    }

    companion object {
        private val systemChunkingEnabled = BooleanUtils.toBoolean(
            System.getProperty("surf.rabbitmq.outgoingPacketChunkingEnabled", "false"),
            "true",
            "false"
        )

        fun create(path: Path) = surfConfigApi.createSpongeYmlConfig<RabbitMQConfig>(
            path,
            "rabbitmq.yml"
        )
    }
}