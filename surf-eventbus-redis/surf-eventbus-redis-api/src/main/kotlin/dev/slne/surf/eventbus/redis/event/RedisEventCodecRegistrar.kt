package dev.slne.surf.eventbus.redis.event

import dev.slne.surf.eventbus.redis.util.InternalRedisAPI

/** Internal bridge implemented by the event bus without widening its stable public interface. */
@InternalRedisAPI
interface RedisEventCodecRegistrar {
    fun <E : RedisEvent> registerEventCodec(
        eventType: Class<E>,
        codec: RedisEventCodec<E>
    )

    fun registerEventType(eventType: Class<out RedisEvent>)

    fun freezeEventCodecs()
}
