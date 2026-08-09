package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.config.constraints.MaxNumber
import dev.slne.surf.api.core.config.constraints.MinNumber
import dev.slne.surf.api.core.config.constraints.Trimmed
import dev.slne.surf.api.core.config.type.StringOrDefault
import dev.slne.surf.api.core.config.type.number.IntOr
import dev.slne.surf.eventbus.InternalEventBusApi
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
@InternalEventBusApi
data class EventBusRedisConfig(
    @field:Comment("Redis server hostname or IP address.")
    @Trimmed
    val host: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment("Redis server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    val port: IntOr.Default = IntOr.Default.USE_DEFAULT,
    @field:Comment("Redis password. Leave at the default for a broker without authentication.")
    val password: StringOrDefault = StringOrDefault.USE_DEFAULT,
    @field:Comment(
        """
    The client name this process registers under.

    Shows up in `CLIENT LIST` on the broker, so a connection can be traced back to the process
    that opened it. The per-plugin suffix is appended automatically.
    """,
    )
    @Trimmed
    val clientName: StringOrDefault = StringOrDefault.USE_DEFAULT,
) {
    override fun toString(): String = "EventBusRedisConfig(host=$host, port=$port, password=<redacted>, clientName=$clientName)"
}
