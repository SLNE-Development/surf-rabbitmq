package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.environment.env

object RedisEnvironment {
    val host by env.optional().named("SURF_EVENTBUS_REDIS_HOST")
    val port by env.optionalInt {
        require("Port must be between 0 and 65535") { it in 0..65535 }
    }.named("SURF_EVENTBUS_REDIS_PORT")
    val password by env.optional(sensitive = true).named("SURF_EVENTBUS_REDIS_PASSWORD")
    val clientName by env.optional().named("SURF_EVENTBUS_REDIS_CLIENT_NAME")
}
