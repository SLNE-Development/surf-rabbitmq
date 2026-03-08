package dev.slne.surf.rabbitmq.api.internal.config

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.surfapi.core.api.config.createSpongeYmlConfig
import dev.slne.surf.surfapi.core.api.config.surfConfigApi
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
    val connectionName: String = "SurfAmqpClient",
    val requestTimeoutSeconds: Long = 60.seconds.inWholeSeconds,
    val serverPrefetchCount: Short = 32,
    val persistRequests: Boolean = true,
    val persistResponses: Boolean = false
) {
    companion object {
        fun create(path: Path) = surfConfigApi.createSpongeYmlConfig<RabbitMQConfig>(
            path,
            "rabbitmq.yml"
        )
    }
}