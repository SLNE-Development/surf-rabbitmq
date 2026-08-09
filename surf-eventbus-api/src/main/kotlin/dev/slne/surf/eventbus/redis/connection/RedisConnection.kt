package dev.slne.surf.eventbus.redis.connection

import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.connection.EventBusConnection
import dev.slne.surf.eventbus.redis.util.Initializable
import dev.slne.surf.eventbus.redis.util.RedisDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.redisson.Redisson
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.api.RedissonReactiveClient
import org.redisson.api.redisnode.RedisNodes
import org.redisson.codec.BaseEventCodec
import org.redisson.config.Config

/**
 * The Redisson half of a `SurfRedisApi`.
 *
 * Split out so Redis reaches the broker the way RabbitMQ does — through an
 * [EventBusConnection] its transporter drives — rather than inlining `Redisson.create`,
 * component initialisation and shutdown into the same class that hands out sync structures.
 * The api above it is now registration and serialization; everything client-shaped is here.
 */
@InternalEventBusApi
class RedisConnection(
    private val config: Config,
) : EventBusConnection {
    private val log = logger()

    /**
     * Underlying Redisson client.
     *
     * Initialized by [connect]; accessing it before then fails.
     *
     * Internal: Redisson is this library's mechanism, not its contract. Published, it put
     * `org.redisson.api.RedissonClient` in the ABI — and the shadow jar relocates that package,
     * so the type named in the published signature was one no consumer could resolve. The core
     * module still uses it; consumers get the bus's own surface instead.
     */
    lateinit var redisson: RedissonClient
        private set

    /**
     * Reactive Redisson client, derived from [redisson] during [connect].
     *
     * Intended for reactive command and Pub/Sub usage in internal components.
     */
    lateinit var redissonReactive: RedissonReactiveClient
        private set

    /**
     * Redis OS type as reported by `INFO server`, used for Redisson codec behaviour.
     *
     * Populated during [connect]. Stays `null` when no special handling is required.
     */
    var redisOsType: BaseEventCodec.OSType? = null
        private set

    // Weak keys: a structure the consumer dropped must not be kept alive by the registry that
    // only exists to initialise and dispose it.
    private val initializables = Caffeine.newBuilder().weakKeys().build<Initializable, Unit>()
    private val disposables = Caffeine.newBuilder().weakKeys().build<RedisDisposable, Unit>()

    /**
     * Whether the client is initialized and not shutting down.
     *
     * Local client state only — it says nothing about whether the broker is reachable. Use
     * [isAlive] for that.
     */
    val isConnected get() = ::redisson.isInitialized && !redisson.isShuttingDown

    /** Brings [component] up during [connect], or immediately when already connected. */
    fun registerInitializable(component: Initializable) {
        initializables.put(component, Unit)
    }

    /** Disposes [component] during [disconnect]. */
    fun registerDisposable(component: RedisDisposable) {
        disposables.put(component, Unit)
    }

    override suspend fun connect() {
        // Not merely redundant: a second Redisson.create() would overwrite the field holding
        // the first client, leaking its connection pool and event loop with no way to reach
        // them again. The transporter guards frozen and disconnected; this one is ours.
        check(!isConnected) { "Redis client is already connected" }

        log.atInfo().log("Connecting to Redis...")

        redisson = withContext(Dispatchers.IO) { Redisson.create(config) }
        redissonReactive = redisson.reactive()

        fetchRedisOs()
        initializeComponents()
    }

    override suspend fun disconnect() {
        if (!isConnected) return

        try {
            disposeComponents()
        } finally {
            withContext(Dispatchers.IO) { redisson.shutdown() }
        }
    }

    /**
     * Performs an active health check by sending a `PING` and awaiting the response.
     *
     * @return `true` if Redis responds successfully, `false` otherwise.
     */
    suspend fun isAlive(): Boolean =
        try {
            withContext(Dispatchers.IO) {
                redisson.getRedisNodes(RedisNodes.SINGLE).pingAll()
            }
        } catch (_: Exception) {
            false
        }

    /** Brings [component] up right now, for a structure created after [connect] already ran. */
    suspend fun initialize(component: Initializable) {
        try {
            component.init()
        } catch (throwable: Throwable) {
            log
                .atSevere()
                .withCause(throwable)
                .log(
                    "Failed to initialize Redis component: %s",
                    component::class.qualifiedName ?: component.toString(),
                )
            throw throwable
        }
    }

    private suspend fun initializeComponents() {
        val components = initializables.asMap().keys
        if (components.isEmpty()) {
            log
                .atInfo()
                .log("No initializable Redis components registered; skipping initialization step.")
            return
        }

        // Concurrently, as the Mono.when() this replaced did: a structure's init is a round
        // trip, and a process with many of them should not pay for them serially.
        try {
            coroutineScope {
                components.map { component -> async { initialize(component) } }.awaitAll()
            }
        } catch (throwable: Throwable) {
            log
                .atSevere()
                .withCause(throwable)
                .log("Redis connect failed because one or more components could not be initialized.")
            throw throwable
        }
    }

    private fun disposeComponents() {
        var cleanupFailure: Throwable? = null
        val components = disposables.asMap().keys

        components.forEach { component ->
            try {
                component.dispose()
            } catch (failure: Throwable) {
                val current = cleanupFailure
                if (current == null) cleanupFailure = failure else current.addSuppressed(failure)
            }
        }
        components.clear()

        cleanupFailure?.let { throw it }
    }

    private suspend fun fetchRedisOs() {
        val os =
            withContext(Dispatchers.IO) {
                redisson.script.eval<String?>(
                    RScript.Mode.READ_ONLY,
                    FETCH_OS_LUA,
                    RScript.ReturnType.STRING,
                )
            }

        redisOsType =
            when {
                os == null || os.contains("Windows") -> BaseEventCodec.OSType.WINDOWS
                os.contains("NONSTOP") -> BaseEventCodec.OSType.HPNONSTOP
                else -> null
            }
    }

    // Annotated as well as the class: the ABI filter excludes annotated declarations, and a
    // companion is a separate class, so without this the dump grew an empty
    // `RedisConnection$Companion` for a type consumers are not meant to see at all.
    @InternalEventBusApi
    companion object {
        /**
         * Reads the `os:` line out of `INFO server`.
         *
         * The character class needs **single** backslashes. Kotlin raw strings do not process
         * escapes, so `[^\\r\\n]` reached Lua verbatim; Lua's own string literal then collapsed
         * `\\` to one backslash, leaving the class `[^\rn]` where `\` is a literal backslash -
         * Lua patterns escape with `%`, not `\`. The class therefore excluded backslash, `r`
         * and `n` instead of CR and LF, so `os:Linux 5.15…` matched `"Li"` and `os:Windows…`
         * matched `"Wi"`. `contains("Windows")` was never true, and the `os == null` branch that
         * would have caught it never ran either, because the match succeeded - with the wrong
         * value.
         */
        @Language("Redis")
        const val FETCH_OS_LUA =
            "local info = redis.call('INFO', 'server')\n" +
                "return string.match(info, 'os:([^\\r\\n]+)')"
    }
}
