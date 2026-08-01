package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.redis.RedisApi
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The single entry point to distributed communication.
 *
 * Three promises, three verbs: [publish] notifies everyone over Redis, [rpc] calls one known
 * place over RabbitMQ, [query] asks everyone and is answered by whoever is responsible. Which
 * transport carries what follows from the verb, not from configuration.
 *
 * The name says `EventBus` while the bus carries more than events; it follows the artifact and
 * package root, which are both `eventbus`.
 *
 * Interface-only: the implementation lives in `surf-eventbus-bus-core` and is reached through
 * [SurfEventBusFactory], discovered via `ServiceLoader`, so this module never depends on it.
 */
interface SurfEventBus {

    /** Registers `@SurfSubscribe` methods on [listener]. Requires the Redis transport. */
    fun subscribe(listener: Any)

    /** Offers an `@RpcService` or `@QueryService` contract. The descriptor decides which. */
    fun <T : Any> registerService(contract: KClass<T>, implementation: T)

    /** Publishes to every matching subscriber. Requires the Redis transport. */
    suspend fun publish(event: SurfBusEvent)

    /** A client proxy for a `@QueryService` contract. Requires the Redis transport. */
    fun <T : Any> query(contract: KClass<T>): T

    /** A client proxy for an `@RpcService` contract. Requires the RabbitMQ transport. */
    fun <T : Any> rpc(contract: KClass<T>): T

    /** Closes registration and validates it. */
    fun freeze()

    suspend fun connect()

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    suspend fun disconnect()

    /** The RabbitMQ-only surface. Throws when the transport is not enabled. */
    val rabbit: SurfRabbitApi

    /** The Redis-only surface: sync structures, caches. Throws when the transport is not enabled. */
    val redis: RedisApi

    companion object {
        fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder =
            SurfEventBusFactory.instance.builder(serviceName, dataPath)
    }
}

inline fun <reified T : Any> SurfEventBus.registerService(implementation: T) =
    registerService(T::class, implementation)

inline fun <reified T : Any> SurfEventBus.query(): T = query(T::class)

inline fun <reified T : Any> SurfEventBus.rpc(): T = rpc(T::class)

inline fun <reified L : Any> SurfEventBus.subscribe() {
    val instance = L::class.objectInstance
        ?: error("${L::class.simpleName} is not a Kotlin object; pass the instance to subscribe(listener)")
    subscribe(instance)
}
