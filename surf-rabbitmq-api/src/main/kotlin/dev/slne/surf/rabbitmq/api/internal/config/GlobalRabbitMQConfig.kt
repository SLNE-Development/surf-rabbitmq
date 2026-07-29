package dev.slne.surf.rabbitmq.api.internal.config

import dev.slne.surf.api.core.config.constraints.MaxNumber
import dev.slne.surf.api.core.config.constraints.MinNumber
import dev.slne.surf.api.core.config.constraints.PositiveNumber
import dev.slne.surf.api.core.config.constraints.Trimmed
import dev.slne.surf.api.core.config.createSpongeYmlConfig
import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.api.core.config.type.BooleanOrDefault
import dev.slne.surf.api.core.config.type.number.IntOr
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@InternalRabbitMQ
@ConfigSerializable
data class GlobalRabbitMQConfig(
    @field:Comment("RabbitMQ server hostname or IP address.")
    @Trimmed
    @JvmField
    val host: String = "localhost",

    @field:Comment("RabbitMQ server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    @JvmField
    val port: Int = 5672,

    @field:Comment("RabbitMQ username used for authentication.")
    @Trimmed
    @JvmField
    val username: String = "guest",

    @field:Comment("RabbitMQ password used for authentication.")
    @JvmField
    val password: String = "guest",

    @field:Comment("RabbitMQ virtual host. The default virtual host is `/`.")
    @Trimmed
    @JvmField
    val vhost: String = "/",

    @field:Comment(
        """
    Connection timeout in seconds.
    
    This controls how long the RabbitMQ client waits while establishing the
    initial connection.
    """
    )
    @PositiveNumber
    @JvmField
    val timeout: IntOr.Default = IntOr.Default.USE_DEFAULT,

    @field:Comment(
        """
    Request timeout in seconds.
    
    Requests that do not receive a response within this time are completed
    with a timeout exception. The same value is also used as the expiration
    for queued request messages.
    """
    )
    @PositiveNumber
    @JvmField
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

    override fun getHost(): String = host
    override fun getPort(): Int = port
    override fun getUsername(): String = username
    override fun getPassword(): String = password
    override fun getVhost(): String = vhost
    override fun getTimeout(): Int = timeout or 30.seconds.inWholeSeconds.toInt()
    override fun getRequestTimeoutSeconds(): Int = requestTimeoutSeconds or 60.seconds.inWholeSeconds.toInt()
    override fun getPublisherPoolSize(): Int = publisherPoolSize or 2
    override fun getServerPrefetchCount(): Int = serverPrefetchCount or 128
    override fun isPersistRequests(): Boolean = persistRequests or true
    override fun isPersistResponses(): Boolean = persistResponses or false

    override fun isOutgoingRequestChunkingEnabled(): Boolean {
        return outgoingRequestChunkingEnabled or systemBoolean(
            name = "surf.rabbitmq.outgoingRequestChunkingEnabled",
            default = false,
        )
    }

    override fun isOutgoingResponseChunkingEnabled(): Boolean {
        return outgoingResponseChunkingEnabled or systemBoolean(
            name = "surf.rabbitmq.outgoingResponseChunkingEnabled",
            default = true,
        )
    }

    override fun toString(): String {
        return "GlobalRabbitMQConfig(host=$host, port=$port, username=$username, password=<redacted>, " +
                "vhost=$vhost, timeout=$timeout, requestTimeoutSeconds=$requestTimeoutSeconds, " +
                "publisherPoolSize=$publisherPoolSize, serverPrefetchCount=$serverPrefetchCount, " +
                "persistRequests=$persistRequests, persistResponses=$persistResponses, " +
                "outgoingRequestChunkingEnabled=$outgoingRequestChunkingEnabled, " +
                "outgoingResponseChunkingEnabled=$outgoingResponseChunkingEnabled)"
    }

    @InternalRabbitMQ
    companion object {
        // Keyed by (path, fileName) rather than a single instance: the first load used to win
        // and every later (path, fileName) was silently ignored, which made configFileName()
        // a no-op and forced every SurfRabbitApi in one JVM (a Paper server full of plugins)
        // to share whichever file loaded first.
        private val cache = ConcurrentHashMap<Pair<Path, String>, GlobalRabbitMQConfig>()

        @Volatile
        private var lastLoaded: GlobalRabbitMQConfig? = null

        fun getOrLoad(path: Path, fileName: String): GlobalRabbitMQConfig {
            val key = path.toAbsolutePath().normalize() to fileName

            return cache.computeIfAbsent(key) {
                surfConfigApi.createSpongeYmlConfig<GlobalRabbitMQConfig>(
                    configFolder = path,
                    configFileName = fileName
                )
            }.also { lastLoaded = it }
        }

        fun getConfig(): GlobalRabbitMQConfig {
            return lastLoaded ?: error("RabbitMQ config not initialized.")
        }

        private fun systemBoolean(name: String, default: Boolean): Boolean {
            val rawValue = System.getProperty(name) ?: return default
            return when {
                rawValue.equals("true", ignoreCase = true) -> true
                rawValue.equals("false", ignoreCase = true) -> false
                else -> throw IllegalArgumentException(
                    "System property $name must be either 'true' or 'false'."
                )
            }
        }
    }
}
