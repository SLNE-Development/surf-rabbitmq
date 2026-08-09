package dev.slne.surf.eventbus.credentials.provider

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.settings.RedisSettings
import dev.slne.surf.eventbus.credentials.RedisCredentials
import org.redisson.misc.RedisURI
import java.nio.file.Path

@InternalEventBusApi
interface RedisCredentialsProvider : CredentialsProvider<RedisCredentials, RedisSettings> {
    /**
     * The four-layer settings for a consumer rooted at [pluginDataPath].
     *
     * `env > plugin yaml > global yaml > default`, the same order RabbitMQ resolves in. The
     * path is what the plugin layer is read from; `null` means the caller has no folder of its
     * own and drops to `env > global > default`.
     *
     * This is the api module's only route to the resolution, which lives in the Redis half —
     * the same inversion [dev.slne.surf.eventbus.redis.RedisComponentProvider] uses, and the
     * reason a [RedisSettings] can be threaded from the bus builder at all.
     */
    fun settings(pluginDataPath: Path?): RedisSettings

    /**
     * The broker URI for already-resolved [credentials].
     *
     * Takes the credentials rather than the settings, so a provider that sources the password
     * from a vault actually gets it into the URI. Taking the settings meant the override in
     * [credentials] was computed and then thrown away.
     */
    fun redisURI(credentials: RedisCredentials): RedisURI

    // Annotated as well as the interface: the ABI filter excludes annotated declarations, and
    // the companion generated for `by redisProvider` is a separate class. Without this the
    // interface vanished from the dump while the companion kept re-exporting every one of its
    // methods - which is how org.redisson.misc.RedisURI stayed in the published surface.
    @InternalEventBusApi
    companion object : RedisCredentialsProvider by redisProvider {
        val INSTANCE get() = redisProvider
    }
}

private val redisProvider = requiredService<RedisCredentialsProvider>()
