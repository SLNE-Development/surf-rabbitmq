package dev.slne.surf.rabbitmq.wrapper.config

import dev.slne.surf.surfapi.core.api.config.createSpongeYmlConfig
import dev.slne.surf.surfapi.core.api.config.surfConfigApi
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal data class RabbitMQConfig(
    val host: String = "localhost",
    val port: Int = 5672,
    val username: String = "guest",
    val password: String = "guest",
    val vhost: String = "/",
    val timeout: Long = 60.seconds.inWholeSeconds,
    val connectionName: String = "SurfAmqpClient"
) {
    companion object {
        fun create(path: Path) = surfConfigApi.createSpongeYmlConfig<RabbitMQConfig>(
            path,
            "rabbitmq.yml"
        )
    }
}