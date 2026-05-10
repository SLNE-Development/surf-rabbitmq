package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.api.core.config.createSpongeYmlConfig
import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@ConfigSerializable
@InternalRabbitMQ
data class RabbitMQConfig(
    val host: String = "localhost",
    val port: Int = 5672,
    val username: String = "guest",
    val password: String = "guest",
    val vhost: String = "/",
    val timeout: Long = 60.seconds.inWholeSeconds,
    val requestTimeoutSeconds: Long = 60.seconds.inWholeSeconds,
    val serverPrefetchCount: Short = 128,
    val persistRequests: Boolean = true,
    val persistResponses: Boolean = false,
    val maxPacketChunkSizeBytes: Int = 128 * 1024
) {
    companion object {
        fun create(path: Path) = surfConfigApi.createSpongeYmlConfig<RabbitMQConfig>(
            path,
            "rabbitmq.yml"
        )
    }
}
