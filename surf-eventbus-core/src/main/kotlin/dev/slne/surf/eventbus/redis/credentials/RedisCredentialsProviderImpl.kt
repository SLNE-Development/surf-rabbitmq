package dev.slne.surf.eventbus.redis.credentials

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.config.settings.RedisSettings
import dev.slne.surf.eventbus.credentials.RedisCredentials
import dev.slne.surf.eventbus.credentials.provider.RedisCredentialsProvider
import dev.slne.surf.eventbus.redis.config.redisSettingsFor
import org.redisson.misc.RedisURI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Reads the credentials straight off the resolved settings.
 *
 * The default. Everything interesting happens in whatever replaces this registration.
 */
@AutoService(RedisCredentialsProvider::class)
class RedisCredentialsProviderImpl : RedisCredentialsProvider {
    override fun settings(pluginDataPath: Path?) = redisSettingsFor(pluginDataPath)

    override fun credentials(config: RedisSettings) =
        RedisCredentials(
            host = config.host,
            port = config.port,
            password = config.password,
        )

    override fun redisURI(credentials: RedisCredentials): RedisURI = redisUriOf(credentials)
}

/**
 * Builds the broker URI from the resolved credentials.
 *
 * Separate from the `@AutoService` class so it can be tested without a registered service and
 * without a live broker — the bug it fixes is a string-building one.
 */
fun redisUriOf(credentials: RedisCredentials): RedisURI =
    RedisURI(
        buildString {
            append(RedisURI.REDIS_PROTOCOL)
            val password = credentials.password
            if (!password.isNullOrEmpty()) {
                // The colon is not decoration: without it the value lands in the *user* slot,
                // so the broker sees a user named after the password and no password at all.
                append(':')
                append(URLEncoder.encode(password, StandardCharsets.UTF_8))
                append('@')
            }
            append(credentials.host)
            append(':')
            append(credentials.port)
        },
    )
