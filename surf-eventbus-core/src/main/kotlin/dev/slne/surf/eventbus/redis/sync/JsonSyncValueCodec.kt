package dev.slne.surf.eventbus.redis.sync

import dev.slne.surf.eventbus.redis.SurfRedisApi
import kotlinx.serialization.KSerializer
import java.util.*

internal class JsonSyncValueCodec<T : Any>(
    private val api: SurfRedisApi,
    private val serializer: KSerializer<T>,
) : SyncValueCodec<T> {
    override val descriptor: String? = null

    override fun encode(value: T): String = api.json.encodeToString(serializer, value)

    override fun decode(value: String): T = api.json.decodeFromString(serializer, value)
}
