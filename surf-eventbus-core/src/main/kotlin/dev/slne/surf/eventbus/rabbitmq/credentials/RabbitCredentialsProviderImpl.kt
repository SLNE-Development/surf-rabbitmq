package dev.slne.surf.eventbus.rabbitmq.credentials

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.config.RabbitMQSettings
import dev.slne.surf.eventbus.credentials.RabbitCredentials
import dev.slne.surf.eventbus.credentials.RabbitCredentialsProvider

/**
 * Reads the credentials straight off the resolved settings.
 *
 * The default. Everything interesting happens in whatever replaces this registration.
 */
@AutoService(RabbitCredentialsProvider::class)
class RabbitCredentialsProviderImpl : RabbitCredentialsProvider {
    override fun credentials(config: RabbitMQSettings) = RabbitCredentials(
        host = config.host,
        port = config.port,
        username = config.username,
        password = config.password,
        vhost = config.vhost,
    )
}
