package dev.slne.surf.eventbus.redis.sync

import dev.slne.surf.eventbus.redis.codec.RedisCodec
import java.util.*

internal interface SyncValueCodec<T : Any> {
    val descriptor: String?

    fun encode(value: T): String

    fun decode(value: String): T
}

internal fun codecDescriptor(codec: RedisCodec<*>): String {
    val id = codec.codecId
    require(id.isNotBlank()) { "Redis codec ID must not be blank" }
    require(id.length <= 512) { "Redis codec ID is too long (${id.length}); maximum is 512 characters" }
    require(codec.version > 0) { "Redis codec version must be positive: ${codec.version}" }
    return "surf-codec-v1:${id.length}:$id:${codec.version}"
}
