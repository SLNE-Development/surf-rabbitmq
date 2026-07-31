package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.config.SpongeYmlConfigClass
import dev.slne.surf.eventbus.redis.RedisInstance
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import java.util.*

@ConfigSerializable
data class RedisConfig(
    val host: String = "localhost",
    val port: Int = 6379,
    val password: String? = null,
    val clientName: String = "surf-redis-client-${UUID.randomUUID()}",
) {
    companion object : SpongeYmlConfigClass<RedisConfig>(
        RedisConfig::class.java,
        RedisInstance.instance.dataPath,
        "config.yml"
    )
}

/** `env > plugin yaml > global yaml > default`, resolved once per process. */
val redisConfig by lazy {
    resolveRedisConfig(global = RedisConfig.getConfig(), plugin = null)
}