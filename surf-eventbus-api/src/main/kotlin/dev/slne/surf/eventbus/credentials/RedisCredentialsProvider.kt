package dev.slne.surf.eventbus.credentials

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.RedisSettings
import org.redisson.misc.RedisURI
import java.nio.file.Path

@InternalEventBusApi
interface RedisCredentialsProvider : CredentialsProvider {

    /**
     * The four-layer settings for a consumer rooted at [pluginDataPath].
     *
     * `env > plugin yaml > global yaml > default`, the same order RabbitMQ resolves in. The
     * path is what the plugin layer is read from; `null` means the caller has no folder of its
     * own and drops to `env > global > default`.
     *
     * This is the api module's only route to the resolution, which lives in the Redis half —
     * the same inversion [RedisComponentProvider] uses, and the reason a `RedisSettings` can be
     * threaded from the bus builder at all.
     */
    fun settings(pluginDataPath: Path?): RedisSettings

    /**
     * The broker URI for already-resolved [settings].
     *
     * Takes the settings rather than reaching for a process-wide value of its own. The
     * implementation used to read the `redisConfig` global directly, which is why the plugin
     * layer could not reach it: by the time this was called the caller's identity — and so
     * which `eventbus-plugin.yml` applied — had been thrown away.
     */
    fun redisURI(settings: RedisSettings): RedisURI

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
