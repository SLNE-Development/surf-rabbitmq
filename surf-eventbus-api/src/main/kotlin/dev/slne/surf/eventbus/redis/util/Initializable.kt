package dev.slne.surf.eventbus.redis.util

import reactor.core.publisher.Mono

@InternalRedisAPI
interface Initializable {
    fun init(): Mono<Void>
}