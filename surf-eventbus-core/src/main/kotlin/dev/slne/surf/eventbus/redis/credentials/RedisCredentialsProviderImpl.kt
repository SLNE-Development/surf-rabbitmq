package dev.slne.surf.eventbus.redis.credentials

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.config.RedisSettings
import dev.slne.surf.eventbus.credentials.RedisCredentialsProvider
import dev.slne.surf.eventbus.redis.config.redisConfig
import org.redisson.misc.RedisURI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@AutoService(RedisCredentialsProvider::class)
class RedisCredentialsProviderImpl : RedisCredentialsProvider {
    override fun redisURI(): RedisURI = redisUriOf(redisConfig)
}

/**
 * Builds the broker URI from the resolved settings.
 *
 * Separate from the `@AutoService` class so it can be tested without a registered service and
 * without a live broker — the bug it fixes is a string-building one.
 */
fun redisUriOf(config: RedisSettings): RedisURI = RedisURI(
    buildString {
        append(RedisURI.REDIS_PROTOCOL)
        val password = config.password
        if (!password.isNullOrEmpty()) {
            // The colon is not decoration: without it the value lands in the *user* slot,
            // so the broker sees a user named after the password and no password at all.
            append(':')
            append(URLEncoder.encode(password, StandardCharsets.UTF_8))
            append('@')
        }
        append(config.host)
        append(':')
        append(config.port)
    }
)
