package dev.slne.surf.eventbus.core

import java.nio.file.Path

/** Implemented once, by `surf-eventbus-redis-core`, and discovered via `@AutoService`. */
interface RedisTransportProvider {
    /**
     * The transports for a consumer rooted at [pluginDataPath].
     *
     * Takes the path because the settings behind the connection are resolved four-layered, and
     * the plugin layer is that folder's `eventbus-plugin.yml`. The provider used to build one
     * `RedisApi` in its constructor from a process-wide config, which is why two plugins on one
     * server shared a connection neither of them could configure.
     */
    fun create(pluginDataPath: Path): RedisTransports
}
