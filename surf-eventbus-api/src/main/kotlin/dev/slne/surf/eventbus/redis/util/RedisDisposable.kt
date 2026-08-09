package dev.slne.surf.eventbus.redis.util

/**
 * Something with background work that has to be released.
 *
 * This exists so that `SyncStructure`, `SimpleRedisCache` and `SimpleSetRedisCache` do not have
 * to extend `reactor.core.Disposable`. They did, which put Reactor in the published ABI of a
 * library that does not otherwise ask its consumers to know Reactor exists — and, because the
 * shadow jar excludes `reactor.core` while relocating everything around it, the supertype named
 * in the published signature was one no consumer could resolve.
 *
 * The shape is deliberately the same as Reactor's, so the implementations in the core module
 * can satisfy both while Reactor remains their internal mechanism.
 */
interface RedisDisposable {

    /** Releases everything this holds. Idempotent. */
    fun dispose()

    /** Whether [dispose] has run. */
    fun isDisposed(): Boolean
}
