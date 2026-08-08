package dev.slne.surf.eventbus.redis.sync

import kotlinx.coroutines.reactive.awaitFirstOrNull
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.util.DisposableAware
import org.jetbrains.annotations.MustBeInvokedByOverriders
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration


abstract class AbstractSyncStructure<L, R : AbstractSyncStructure.VersionedSnapshot>(
    protected val api: RedisApi,
    id: String,
    override val ttl: Duration
) : DisposableAware(), SyncStructure<L> {
    companion object {
        /**
         * Key prefix for every synchronised structure.
         *
         * Renamed from `surf-redis:sync:` in 2.0, when `surf-redis` and `surf-rabbitmq` became
         * one project. This is a **wire break**: keys under the old prefix are invisible to a
         * 2.0 process and vice versa, so a mixed fleet silently runs two disjoint copies of
         * every sync structure. Restart the whole fleet together, or accept that structures
         * diverge until you do. See `docs/rollout-2.0.md`.
         */
        const val NAMESPACE = "surf.eventbus.sync:"
        private val log = logger()
    }

    override val id = id.replace(":", "_")

    private val listeners = CopyOnWriteArrayList<(L) -> Unit>()
    protected val lock = ReentrantReadWriteLock()

    private val listenerIds = ConcurrentHashMap.newKeySet<Int>()

    @MustBeInvokedByOverriders
    override suspend fun init() {
        registerListeners().awaitFirstOrNull()
        loadFromRemote().awaitFirstOrNull()
    }

    private fun registerListeners(): Mono<Void> = Flux.merge(registerListeners0())
        .doOnError { e ->
            log.atSevere()
                .withCause(e)
                .log("Failed to register listeners for $id")
        }
        .doOnNext { listenerIds.add(it) }
        .then()

    private fun unregisterListeners(): Mono<Void> = Flux.fromIterable(listenerIds)
        .concatMap { unregisterListener(it).thenReturn(it) }
        .doOnError { e ->
            log.atSevere()
                .withCause(e)
                .log("Failed to unregister listeners for $id")
        }
        .doOnNext { listenerIds.remove(it) }
        .then()

    protected abstract fun registerListeners0(): List<Mono<Int>>
    protected abstract fun unregisterListener(id: Int): Mono<*>

    @MustBeInvokedByOverriders
    override fun dispose0() {
        unregisterListeners().block()
    }

    override fun addListener(listener: (L) -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: (L) -> Unit) {
        listeners -= listener
    }

    protected fun notifyListeners(value: L) {
        for (listener in listeners) {
            try {
                listener(value)
            } catch (e: Throwable) {
                log.atSevere()
                    .withCause(e)
                    .log("Error notifying listener for $id")
            }
        }
    }

    protected fun loadFromRemote(): Mono<Void> = loadFromRemote0()
        .onErrorResume {
            log.atWarning()
                .withCause(it)
                .log("Failed to load remote state for $id")
            Mono.empty()
        }
        .doOnSuccess { raw ->
            if (raw != null) {
                overrideFromRemote(raw)
            }
        }
        .then()

    protected abstract fun loadFromRemote0(): Mono<R>
    protected abstract fun overrideFromRemote(raw: R)


    interface VersionedSnapshot {
        val version: Long
    }

    data class SimpleVersionedSnapshot<V>(
        val value: V,
        override val version: Long
    ) : VersionedSnapshot {
        companion object {
            fun <V : Any> fromTuple(tuple: Tuple2<V, Long>) =
                SimpleVersionedSnapshot(tuple.t1, tuple.t2)
        }
    }
}