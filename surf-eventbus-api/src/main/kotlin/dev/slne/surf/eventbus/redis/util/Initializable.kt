package dev.slne.surf.eventbus.redis.util

import dev.slne.surf.eventbus.InternalEventBusApi
import reactor.core.publisher.Mono

@InternalEventBusApi
interface Initializable {
    fun init(): Mono<Void>
}