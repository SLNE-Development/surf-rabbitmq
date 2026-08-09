package dev.slne.surf.eventbus.credentials.provider

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.settings.RabbitMQSettings
import dev.slne.surf.eventbus.credentials.RabbitCredentials

/**
 * Where the RabbitMQ connection gets its address and secret.
 *
 * Returns the credentials rather than a `ConnectionFactory`: the AMQP client is a
 * `surf-eventbus-core` dependency, and the api module has no business growing one just to
 * describe a seam. It also matches [RedisCredentialsProvider], which hands back a `RedisURI`
 * and lets the caller build the client.
 *
 * Replacing the `@AutoService` registration is how an operator sources the password from a
 * vault instead of a file — the thing Redis could already do and RabbitMQ could not.
 */
interface RabbitCredentialsProvider : CredentialsProvider<RabbitCredentials, RabbitMQSettings> {
    @InternalEventBusApi
    companion object : RabbitCredentialsProvider by provider {
        val INSTANCE get() = provider
    }
}

private val provider = requiredService<RabbitCredentialsProvider>()
