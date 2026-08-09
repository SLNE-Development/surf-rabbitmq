package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.config.constraints.MaxNumber
import dev.slne.surf.api.core.config.constraints.MinNumber
import dev.slne.surf.api.core.config.constraints.PositiveNumber
import dev.slne.surf.api.core.config.constraints.Trimmed
import dev.slne.surf.api.core.config.type.BooleanOrDefault
import dev.slne.surf.api.core.config.type.StringOrDefault
import dev.slne.surf.api.core.config.type.number.IntOr
import dev.slne.surf.eventbus.InternalEventBusApi
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
@InternalEventBusApi
data class EventBusRabbitMQConfig(
    @field:Comment("RabbitMQ server hostname or IP address.")
    @Trimmed
    val host: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment("RabbitMQ server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    val port: IntOr.Default = IntOr.Default.USE_DEFAULT,
    @field:Comment("RabbitMQ username used for authentication.")
    @Trimmed
    val username: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment("RabbitMQ password used for authentication.")
    val password: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment("RabbitMQ virtual host. The default virtual host is `/`.")
    @Trimmed
    val vhost: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment(
        """
    Connection timeout in seconds.

    This controls how long the RabbitMQ client waits while establishing the
    initial connection.
    """,
    )
    @PositiveNumber
    val timeout: IntOr.Default = IntOr.Default.USE_DEFAULT,
    @field:Comment(
        """
    Request timeout in seconds.

    Requests that do not receive a response within this time are completed
    with a timeout exception. The same value is also used as the expiration
    for queued request messages.
    """,
    )
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
    """,
    )
    @PositiveNumber
    val publisherPoolSize: IntOr.Default = IntOr.Default.USE_DEFAULT,
    @field:Comment(
        """
    Maximum number of request messages the server may receive without
    acknowledging them. Only applies to microservices.

    A value of `0` disables the prefetch limit.
    """,
    )
    @MinNumber(0.0)
    @MaxNumber(Short.MAX_VALUE.toDouble())
    val serverPrefetchCount: IntOr.Default = IntOr.Default.USE_DEFAULT,
    @field:Comment(
        """
    Whether request messages should be published as persistent RabbitMQ
    messages.

    Persistent requests survive broker restarts when they are routed to a
    durable queue.
    """,
    )
    val persistRequests: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
    @field:Comment(
        """
    Whether response messages should be published as persistent RabbitMQ
    messages.

    Responses are usually transient because they are sent to temporary
    callback queues.
    """,
    )
    val persistResponses: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
    @field:Comment(
        "Enables publishing large request packets as multiple RabbitMQ messages.\n\nKeep this " +
            "disabled during mixed-version rollouts. Old servers cannot understand chunked request messages.",
    )
    val outgoingRequestChunkingEnabled: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
    @field:Comment(
        "Enables publishing large response packets as multiple RabbitMQ messages.\n\n" +
            "This can usually be enabled during mixed-version rollouts because responses are only chunked when" +
            " the requesting client explicitly advertises support for chunked responses.",
    )
    val outgoingResponseChunkingEnabled: BooleanOrDefault = BooleanOrDefault.USE_DEFAULT,
    @field:Comment(
        "The service name audit reports (failed handlers, unroutable messages, expired chunk " +
            "series) are sent to.",
    )
    @Trimmed
    val auditServiceName: StringOrDefault = StringOrDefault.USE_DEFAULT,
) {
    override fun toString(): String =
        "RabbitMQSection(host=$host, port=$port, username=$username, password=<redacted>, " +
            "vhost=$vhost, timeout=$timeout, requestTimeoutSeconds=$requestTimeoutSeconds, " +
            "publisherPoolSize=$publisherPoolSize, serverPrefetchCount=$serverPrefetchCount, " +
            "persistRequests=$persistRequests, persistResponses=$persistResponses, " +
            "outgoingRequestChunkingEnabled=$outgoingRequestChunkingEnabled, " +
            "outgoingResponseChunkingEnabled=$outgoingResponseChunkingEnabled, " +
            "auditServiceName=$auditServiceName)"
}
