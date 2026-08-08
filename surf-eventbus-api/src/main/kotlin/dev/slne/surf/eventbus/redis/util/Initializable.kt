package dev.slne.surf.eventbus.redis.util

import dev.slne.surf.eventbus.InternalEventBusApi

/**
 * Something the [dev.slne.surf.eventbus.redis.RedisApi] brings up before it hands it out.
 *
 * `suspend` rather than `Mono<Void>`: Reactor is an implementation detail of the Redis half,
 * and returning one from a published interface made it part of this library's ABI — with the
 * added twist that the shadow jar relocates Redisson but excludes Reactor, so the type in the
 * published signature was not the type a consumer could name.
 */
@InternalEventBusApi
interface Initializable {
    suspend fun init()
}
