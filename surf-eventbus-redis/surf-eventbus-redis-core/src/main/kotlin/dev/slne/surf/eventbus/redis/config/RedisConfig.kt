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
    fun overwriteFromEnv() = copy(
        host = RedisEnvironment.host ?: host,
        port = RedisEnvironment.port ?: port,
        password = RedisEnvironment.password ?: password,
        clientName = RedisEnvironment.clientName ?: clientName
    )

    companion object : SpongeYmlConfigClass<RedisConfig>(
        RedisConfig::class.java,
        RedisInstance.instance.dataPath,
        "config.yml"
    )
}

val redisConfig by lazy { RedisConfig.getConfig().overwriteFromEnv() }