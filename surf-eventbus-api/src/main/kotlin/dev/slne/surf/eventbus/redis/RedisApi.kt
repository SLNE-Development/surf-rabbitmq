package dev.slne.surf.eventbus.redis

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.common.flogger.StackSize
import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.serializer.java.uuid.JavaUUIDStringSerializer
import dev.slne.surf.api.core.util.getCallerClass
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.RedisApi.Companion.create
import dev.slne.surf.eventbus.redis.cache.RedisSetIndexes
import dev.slne.surf.eventbus.redis.cache.SimpleRedisCache
import dev.slne.surf.eventbus.redis.cache.SimpleSetRedisCache
import dev.slne.surf.eventbus.redis.codec.RedisCodec
import dev.slne.surf.eventbus.config.RedisSettings
import dev.slne.surf.eventbus.credentials.RedisCredentialsProvider
import dev.slne.surf.eventbus.redis.internal.RedissonConfigDetails
import dev.slne.surf.eventbus.redis.sync.SyncStructure
import dev.slne.surf.eventbus.redis.sync.list.SyncList
import dev.slne.surf.eventbus.redis.sync.map.SyncMap
import dev.slne.surf.eventbus.redis.sync.set.SyncSet
import dev.slne.surf.eventbus.redis.sync.value.SyncValue
import dev.slne.surf.eventbus.redis.util.Initializable
import dev.slne.surf.eventbus.redis.util.RedisDisposable
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.intellij.lang.annotations.Language
import org.redisson.Redisson
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.api.RedissonReactiveClient
import org.redisson.api.redisnode.RedisNodes
import org.redisson.codec.BaseEventCodec
import org.redisson.config.Config
import org.redisson.config.ConfigSupport
import org.redisson.misc.RedisURI
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Central entry point for surf-redis.
 *
 * `RedisApi` owns the underlying Redisson clients and wires up the higher-level surf-redis features:
 * - replicated in-memory data structures ([SyncList], [SyncSet], [SyncMap], [SyncValue])
 * - simple cache helpers ([SimpleRedisCache], [SimpleSetRedisCache])
 *
 * Instances are created via [create] and manage their own lifecycle.
 *
 * ## Lifecycle
 * This API follows a two-phase setup:
 * 1. Create an instance via [create]
 * 2. Register listeners/handlers and create sync structures/caches
 * 3. Call [freeze] to lock configuration
 * 4. Call [connect] to initialize clients and start all registered features
 *
 * Use [freezeAndConnect] as a convenience for steps 3 and 4.
 *
 * Call [disconnect] to shut down all clients and dispose resources.
 *
 * ## Usage
 * A common setup is to provide a single, platform-owned [RedisApi] instance via a service and expose
 * it to consumers. Registrations (listeners / handlers) are done before connecting; consumers then
 * create sync structures where they are needed.
 *
 * ```
 * // Service that owns the RedisApi instance
 * abstract class RedisService {
 *     val redisApi = RedisApi.create()
 *
 *     fun connect() {
 *         register()
 *         redisApi.freezeAndConnect()
 *     }
 *
 *     @MustBeInvokedByOverriders
 *     @ApiStatus.OverrideOnly
 *     protected open fun register() {
 *         // Register listeners / request handlers here
 *         // redisApi.registerRequestHandler(SomeHandler())
 *         //
 *         // Can be overridden by platform implementations to register platform-specific listeners.
 *     }
 *
 *     fun disconnect() {
 *         redisApi.disconnect()
 *     }
 *
 *     companion object {
 *         val instance = requiredService<RedisService>()
 *         fun get() = instance
 *
 *         fun namespaced(suffix: String) = "example-namespace:$suffix"
 *     }
 * }
 *
 * // Convenience accessor used by consumers
 * val redisApi get() = RedisService.get().redisApi
 *
 * // Platform implementation discovered/registered via AutoService
 * @AutoService(RedisService::class)
 * class PaperRedisService : RedisService() {
 *     override fun register() {
 *         super.register()
 *         // redisApi.subscribeToEvents(SomePaperListener())
 *     }
 * }
 *
 * // Consumer creates sync structures at the call site where they are needed
 * object SomeOtherService {
 *     private val someSyncList =
 *         redisApi.createSyncList<String>(RedisService.namespaced("some-sync-list"))
 * }
 * ```
 */
@Suppress("unused")
class RedisApi private constructor(
    private val config: Config,
    /** JSON instance used internally for (de-)serialization. */
    val json: Json,
) {
    private val parsedConfig = ConfigSupport.getConfig(config)

    /**
     * Underlying Redisson client.
     *
     * Initialized by [connect]. Accessing this property before [connect] will fail.
     *
     * Internal: Redisson is this library's mechanism, not its contract. Published, it put
     * `org.redisson.api.RedissonClient` in the ABI — and the shadow jar relocates that package,
     * so the type named in the published signature was one no consumer could resolve. The core
     * module still uses it; consumers get the bus's own surface instead.
     */
    @InternalEventBusApi
    lateinit var redisson: RedissonClient
        private set

    /**
     * Reactive Redisson client, derived from [redisson] during [connect].
     *
     * Intended for reactive command and Pub/Sub usage in internal components.
     */
    @InternalEventBusApi
    lateinit var redissonReactive: RedissonReactiveClient
        private set

    /**
     * Redis OS type as reported by `INFO server` (used for Redisson codec behavior).
     *
     * This is populated during [connect]. It may remain `null` if no special handling is required.
     */
    @InternalEventBusApi
    var redisOsType: BaseEventCodec.OSType? = null
        private set

    /**
     * Identifier of the current client/node as provided by the component provider.
     */
    val clientId get() = RedisComponentProvider.clientId

    private val syncStructureScope = CoroutineScope(
        Dispatchers.Default
                + SupervisorJob()
                + CoroutineName("surf-redis-sync-structures-${parsedConfig.clientName}")
                + CoroutineExceptionHandler { context, throwable ->
            log.atSevere()
                .withCause(throwable)
                .log(
                    "Uncaught exception in Redis sync structure coroutine (context: ${
                        context.toString().replace("{", "[").replace("}", "]")
                    })"
                )
        }
    )

    /**
     * Coroutine scope for Redis listener coroutines, including [RequestContext] instances.
     *
     * The scope is named after the configured client name for easier identification in diagnostics.
     * It is cancelled automatically during [disconnect].
     *
     * **Internal API** — not intended for use outside of surf-redis internals.
     */
    @InternalEventBusApi
    val redisListenerScope = CoroutineScope(
        Dispatchers.Default
                + SupervisorJob()
                + CoroutineName("surf-redis-listeners-${parsedConfig.clientName}")
                + CoroutineExceptionHandler { context, throwable ->
            log.atSevere()
                .withCause(throwable)
                .log(
                    "Uncaught exception in Redis listener coroutine (context: ${
                        context.toString().replace("{", "[").replace("}", "]")
                    })"
                )
        }
    )

    private val initializables = Caffeine.newBuilder().weakKeys().build<Initializable, Unit>()
    private val disposables = Caffeine.newBuilder().weakKeys().build<RedisDisposable, Unit>()

    @Volatile
    private var frozen = false

    @Volatile
    private var disconnected = false

    companion object {
        private val log = logger()

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
        internal const val FETCH_OS_LUA =
            "local info = redis.call('INFO', 'server')\n" +
                    "return string.match(info, 'os:([^\\r\\n]+)')"

        /**
         * Creates a [RedisApi] instance using the given [redisURI].
         *
         * This is the most explicit factory method. The [pluginName] is used for
         * identification and logging purposes and is typically derived automatically
         * via the caller when not provided explicitly.
         *
         * @param redisURI Redis connection URI.
         * @param pluginName Logical name of the calling plugin or component.
         * @param serializerModule Additional serializers to be included in the internal [Json] instance.
         */
        @InternalEventBusApi
        fun create(
            redisURI: RedisURI,
            pluginName: String,
            serializerModule: SerializersModule
        ): RedisApi = create(
            // An explicit URI is the address and nothing else, so everything the config builder
            // reads beyond the address stays at its default. Callers that want a plugin's
            // resolved settings go through the RedisSettings overload instead.
            settings = RedisSettings(
                host = redisURI.host,
                port = redisURI.port,
                password = redisURI.password,
            ),
            redisURI = redisURI,
            pluginName = pluginName,
            serializerModule = serializerModule,
        )

        /**
         * Creates a [RedisApi] from already-resolved four-layer [settings].
         *
         * The path the bus itself takes. The URI is derived from the settings through
         * [RedisCredentialsProvider], so a host that supplies credentials from somewhere other
         * than the yaml still gets a say, and everything else the Redisson config needs —
         * `clientName` — comes from the same resolution rather than from a process-wide value
         * that no plugin layer could reach.
         */
        @InternalEventBusApi
        fun create(
            settings: RedisSettings,
            pluginName: String,
            serializerModule: SerializersModule = EmptySerializersModule()
        ): RedisApi = create(
            settings = settings,
            redisURI = RedisCredentialsProvider.redisURI(settings),
            pluginName = pluginName,
            serializerModule = serializerModule,
        )

        private fun create(
            settings: RedisSettings,
            redisURI: RedisURI,
            pluginName: String,
            serializerModule: SerializersModule
        ): RedisApi {
            val config = RedisComponentProvider.createRedissonConfig(
                RedissonConfigDetails(
                    redisURI = redisURI,
                    settings = settings,
                    serializerModule = serializerModule,
                    pluginName = pluginName
                )
            )

            return RedisApi(config, createJson(serializerModule))
        }

        /**
         * Creates a [RedisApi] instance using the given [redisURI].
         *
         * The plugin name is resolved automatically from the calling class.
         *
         * @param redisURI Redis connection URI.
         * @param serializerModule Additional serializers to be included in the internal [Json] instance.
         */
        @InternalEventBusApi
        fun create(
            redisURI: RedisURI,
            serializerModule: SerializersModule = EmptySerializersModule()
        ): RedisApi = create(redisURI, getCallingPluginName(), serializerModule)

        /**
         * Creates a [RedisApi] for an address such as `redis://localhost:6379`.
         *
         * The address-taking counterpart of the `RedisURI` overloads, which are internal:
         * `org.redisson.misc.RedisURI` is a Redisson type, and the shadow jar relocates
         * Redisson, so a consumer compiling against the published artifact could not name the
         * parameter type at all.
         *
         * Named rather than overloaded because `create(String, SerializersModule)` already
         * means "plugin name", and one of the two would silently win.
         *
         * @param address Redis connection URI, e.g. `redis://host:6379`.
         * @param serializerModule Additional serializers for the internal [Json] instance.
         */
        fun createForAddress(
            address: String,
            serializerModule: SerializersModule = EmptySerializersModule()
        ): RedisApi = create(RedisURI(address), getCallingPluginName(), serializerModule)

        /**
         * Creates a [RedisApi] instance using credentials provided by
         * [RedisCredentialsProvider].
         *
         * The plugin name is resolved automatically from the calling class.
         *
         * @param serializerModule Additional serializers to be included in the internal [Json] instance.
         */
        fun create(
            serializerModule: SerializersModule = EmptySerializersModule()
        ): RedisApi = create(
            RedisCredentialsProvider.settings(null), getCallingPluginName(), serializerModule
        )

        /**
         * Creates a [RedisApi] instance using the default Redis credentials.
         *
         * Resolves `env > global > default`: a caller arriving here has named no data folder,
         * so there is no plugin layer to apply. `SurfEventBusBuilder(service, dataPath)
         * .withRedis()` does have one and resolves all four.
         *
         * @param pluginName Logical name of the calling plugin or component.
         */
        fun create(pluginName: String): RedisApi =
            create(RedisCredentialsProvider.settings(null), pluginName, EmptySerializersModule())

        /**
         * Creates a [RedisApi] instance using the default Redis credentials.
         *
         * @param pluginName Logical name of the calling plugin or component.
         * @param serializerModule Additional serializers to be included in the internal [Json] instance.
         */
        fun create(pluginName: String, serializerModule: SerializersModule): RedisApi =
            create(RedisCredentialsProvider.settings(null), pluginName, serializerModule)

        // The path-taking create() overload is gone. It was @Deprecated(level = ERROR), so no
        // code could call it and no code could have been calling it - 2.0 is a breaking release
        // and keeping an uncallable overload only widened the surface.

        @OptIn(ExperimentalSerializationApi::class)
        private fun createJson(serializerModule: SerializersModule) = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            encodeDefaults = true
            serializersModule = SerializersModule {
                include(SurfSerializerModule.all.overwriteWith(SerializersModule {
                    contextual(JavaUUIDStringSerializer) // UUID as string rather than byte array
                }))

                include(serializerModule)
            }
        }

        private fun getCallingPluginName(): String {
            val caller = getCallerClass(1) ?: return "Unknown"
            return RedisComponentProvider.tryExtractPluginNameFromClass(caller)
        }
    }

    /**
     * Initializes the Redis clients and starts all registered features.
     *
     * This method is blocking and must only be called once. A disconnected instance cannot be
     * reconnected because its managed structures and coroutine scopes have been disposed; create a
     * new [RedisApi] for a later connection lifecycle.
     * The API must be [freeze]d before connecting to ensure registrations are complete.
     *
     * During connection:
     * - [redisson] / [redissonReactive] are created
     * - [redisOsType] may be detected
     * - all previously created sync structures are initialized
     *
     * @throws IllegalArgumentException if the API is not frozen
     * @throws IllegalArgumentException if already connected
     */
    suspend fun connect(): RedisApi = apply {
        require(isFrozen()) { "Redis client must be frozen before connecting" }
        require(!isConnected()) { "Redis client already initialized" }
        require(!disconnected) {
            "RedisApi cannot reconnect after disconnect; create and configure a new RedisApi instance"
        }

        log.atInfo()
            .log("Connecting to Redis...")

        redisson = withContext(Dispatchers.IO) { Redisson.create(config) }
        redissonReactive = redisson.reactive()

        try {
            fetchRedisOs()

            val initializables = this.initializables.asMap().keys
            if (initializables.isEmpty()) {
                log.atInfo()
                    .log("No initializable Redis components registered; skipping initialization step.")
            } else {
                // Concurrently, as the Mono.when() this replaced did: a structure's init is a
                // round trip, and a process with many of them should not pay for them serially.
                try {
                    coroutineScope {
                        initializables.map { initializable ->
                            async { initialize(initializable) }
                        }.awaitAll()
                    }
                } catch (throwable: Throwable) {
                    log.atSevere()
                        .withCause(throwable)
                        .log("RedisApi.connect() failed because one or more components could not be initialized.")
                    throw throwable
                }
            }
        } catch (failure: Throwable) {
            try {
                disposeManagedResources()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                withContext(Dispatchers.IO) { redisson.shutdown() }
                disconnected = true
            }
            throw failure
        }
    }

    private suspend fun initialize(initializable: Initializable) {
        try {
            initializable.init()
        } catch (throwable: Throwable) {
            log.atSevere()
                .withCause(throwable)
                .log(
                    "Failed to initialize Redis component: %s",
                    initializable::class.qualifiedName ?: initializable.toString()
                )
            throw throwable
        }
    }

    private suspend fun fetchRedisOs() {
        val os = withContext(Dispatchers.IO) {
            redisson.script.eval<String?>(
                RScript.Mode.READ_ONLY,
                FETCH_OS_LUA,
                RScript.ReturnType.STRING,
            )
        }

        if (os == null || os.contains("Windows")) {
            redisOsType = BaseEventCodec.OSType.WINDOWS
        } else if (os.contains("NONSTOP")) {
            redisOsType = BaseEventCodec.OSType.HPNONSTOP
        }
    }

    /** Convenience method that calls [freeze] and then [connect]. */
    suspend fun freezeAndConnect(): RedisApi = apply {
        freeze()
        connect()
    }

    /**
     * Freezes this API instance and prevents further registrations.
     *
     * After freezing:
     * - creating sync structures via `createSync*` is no longer allowed
     * - [connect] may be called
     *
     * @throws IllegalArgumentException if already frozen
     */
    fun freeze() {
        require(!isFrozen()) { "Redis client already frozen" }

        frozen = true
    }

    /**
     * @return `true` if this API instance has been frozen and no further registrations are allowed.
     */
    fun isFrozen(): Boolean = frozen

    /**
     * Shuts down the Redis clients and disposes all resources created by this API.
     *
     * This method is safe to call multiple times; if not connected it has no effect. Disconnect is
     * terminal for this instance.
     *
     * On disconnect:
     * - the request/response bus is closed
     * - all sync structures are disposed
     * - internal coroutine scopes (`syncStructureScope`, `redisListenerScope`) are cancelled
     * - reactive disposables are disposed
     * - Redisson is shut down
     */
    suspend fun disconnect() {
        if (!isConnected()) return

        try {
            disposeManagedResources()
        } finally {
            withContext(Dispatchers.IO) { redisson.shutdown() }
            disconnected = true
        }
    }

    private fun disposeManagedResources() {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val current = cleanupFailure
                if (current == null) cleanupFailure = failure else current.addSuppressed(failure)
            }
        }

        val disposables = this.disposables.asMap().keys
        disposables.forEach { disposable -> cleanup(disposable::dispose) }
        disposables.clear()

        cleanup { syncStructureScope.cancel("RedisApi disconnected") }
        cleanup { redisListenerScope.cancel("RedisApi disconnected") }

        cleanupFailure?.let { throw it }
    }

    /**
     * Indicates whether the Redis client is initialized and not shutting down.
     *
     * This does not guarantee Redis availability; it only reflects the local client state.
     */
    fun isConnected(): Boolean = ::redisson.isInitialized && !redisson.isShuttingDown

    /**
     * Performs an active health check against Redis.
     *
     * Sends a `PING` and waits for the response.
     *
     * @return `true` if Redis responds successfully, `false` otherwise.
     */
    suspend fun isAlive(): Boolean = try {
        withContext(Dispatchers.IO) {
            redisson.getRedisNodes(RedisNodes.SINGLE).pingAll()
        }
    } catch (_: Exception) {
        false
    }


    /**
     * Creates a new [SyncList] instance identified by [id].
     *
     * Must be called before [freeze].
     *
     * @param id Logical identifier used by the structure (typically part of the Redis key namespace).
     * @param ttl Time-to-live used by the structure implementation.
     */
    inline fun <reified E : Any> createSyncList(
        id: String,
        ttl: Duration = SyncList.DEFAULT_TTL
    ): SyncList<E> = createSyncList(id, json.serializersModule.serializer(), ttl)

    /**
     * Creates a new [SyncList] instance identified by [id].
     *
     * Must be called before [freeze].
     *
     * @param id Logical identifier used by the structure (typically part of the Redis key namespace).
     * @param elementSerializer Serializer used for elements.
     * @param ttl Time-to-live used by the structure implementation.
     */
    fun <E : Any> createSyncList(
        id: String,
        elementSerializer: KSerializer<E>,
        ttl: Duration = SyncList.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncList(id, elementSerializer, ttl, this)
    }

    /**
     * Creates a [SyncList] whose elements are encoded directly with [codec].
     *
     * Every client using the same [id] must use a codec with the same stable ID and version.
     * Incompatible configurations are rejected during [connect].
     */
    fun <E : Any> createSyncList(
        id: String,
        codec: RedisCodec<E>,
        ttl: Duration = SyncList.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncList(id, codec, ttl, this)
    }

    /**
     * Creates a new [SyncSet] instance identified by [id].
     *
     * Must be called before [freeze].
     */
    inline fun <reified E : Any> createSyncSet(
        id: String,
        ttl: Duration = SyncSet.DEFAULT_TTL
    ): SyncSet<E> = createSyncSet(id, json.serializersModule.serializer(), ttl)

    /**
     * Creates a new [SyncSet] instance identified by [id].
     *
     * Must be called before [freeze].
     */
    fun <E : Any> createSyncSet(
        id: String,
        elementSerializer: KSerializer<E>,
        ttl: Duration = SyncSet.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncSet(id, elementSerializer, ttl, this)
    }

    /**
     * Creates a [SyncSet] whose elements are encoded directly with [codec].
     * Incompatible codec identities for the same [id] fail during [connect].
     */
    fun <E : Any> createSyncSet(
        id: String,
        codec: RedisCodec<E>,
        ttl: Duration = SyncSet.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncSet(id, codec, ttl, this)
    }

    /**
     * Creates a new [SyncValue] instance identified by [id].
     *
     * Must be called before [freeze].
     *
     * @param defaultValue Initial/default value used by the implementation.
     */
    inline fun <reified T : Any> createSyncValue(
        id: String,
        defaultValue: T,
        ttl: Duration = SyncValue.DEFAULT_TTL
    ): SyncValue<T> = createSyncValue(id, json.serializersModule.serializer(), defaultValue, ttl)

    /**
     * Creates a new [SyncValue] instance identified by [id].
     *
     * Must be called before [freeze].
     *
     * @param serializer Serializer used for the value.
     * @param defaultValue Initial/default value used by the implementation.
     */
    fun <T : Any> createSyncValue(
        id: String,
        serializer: KSerializer<T>,
        defaultValue: T,
        ttl: Duration = SyncValue.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncValue(id, serializer, defaultValue, ttl, this)
    }

    /**
     * Creates a [SyncValue] encoded directly with [codec]. The codec is used for the initial
     * snapshot, updates, stream replication, resynchronization, and reconnect reloads.
     */
    fun <T : Any> createSyncValue(
        id: String,
        codec: RedisCodec<T>,
        defaultValue: T,
        ttl: Duration = SyncValue.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncValue(id, codec, defaultValue, ttl, this)
    }

    /**
     * Creates a new [SyncMap] instance identified by [id].
     *
     * Must be called before [freeze].
     */
    inline fun <reified K : Any, reified V : Any> createSyncMap(
        id: String,
        ttl: Duration = SyncMap.DEFAULT_TTL
    ): SyncMap<K, V> = createSyncMap(
        id,
        json.serializersModule.serializer(),
        json.serializersModule.serializer(),
        ttl
    )

    /**
     * Creates a new [SyncMap] instance identified by [id].
     *
     * Must be called before [freeze].
     *
     * @param keySerializer Serializer used for keys.
     * @param valueSerializer Serializer used for values.
     */
    fun <K : Any, V : Any> createSyncMap(
        id: String,
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
        ttl: Duration = SyncMap.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncMap(id, keySerializer, valueSerializer, ttl, this)
    }

    /**
     * Creates a [SyncMap] with independently encoded keys and values.
     *
     * Both codecs are used throughout snapshots, Redis hashes, stream changes, resynchronization,
     * and reconnect reloads. Every client using the same [id] must use matching codec identities.
     */
    fun <K : Any, V : Any> createSyncMap(
        id: String,
        keyCodec: RedisCodec<K>,
        valueCodec: RedisCodec<V>,
        ttl: Duration = SyncMap.DEFAULT_TTL
    ) = createSyncStructure {
        RedisComponentProvider.createSyncMap(id, keyCodec, valueCodec, ttl, this)
    }

    /**
     * Creates a new instance of a synchronization structure using the provided creator function.
     * Ensures that the Redis client is not frozen before creating the synchronization structure.
     * The created structure is added to the internal list of sync structures managed by the Redis API.
     *
     * @param creator A factory function responsible for creating a specific type of synchronization structure.
     * @return The newly created synchronization structure of type [S].
     * @throws IllegalStateException if the Redis client is frozen when attempting to create the structure.
     */
    private inline fun <S : SyncStructure<*>> createSyncStructure(creator: () -> S): S {
        require(!isFrozen()) { "Redis client must not be frozen to create sync structures" }
        val structure = creator()
        initializables.put(structure, Unit)
        disposables.put(structure, Unit)
        return structure
    }

    /**
     * Creates a [SimpleRedisCache] for the given [namespace] using a serializer derived from `V`.
     *
     * @param namespace Prefix placed before each Redis key.
     * @param ttl Time-to-live for cache entries.
     * @param keyToString Function that converts a key of type `K` to its string representation.
     */
    inline fun <K : Any, reified V : Any> createSimpleCache(
        namespace: String,
        ttl: Duration,
        noinline keyToString: (K) -> String = { it.toString() }
    ): SimpleRedisCache<K, V> =
        createSimpleCache(namespace, json.serializersModule.serializer(), ttl, keyToString)

    /**
     * Creates a [SimpleRedisCache] for the given [namespace] using the provided [serializer].
     *
     * @param namespace Prefix placed before each Redis key.
     * @param serializer Serializer used for cache values.
     * @param ttl Time-to-live for cache entries.
     * @param keyToString Function that converts a key of type `K` to its string representation.
     */
    fun <K : Any, V : Any> createSimpleCache(
        namespace: String,
        serializer: KSerializer<V>,
        ttl: Duration,
        keyToString: (K) -> String = { it.toString() }
    ): SimpleRedisCache<K, V> {
        val cache =
            RedisComponentProvider.createSimpleCache(namespace, serializer, ttl, keyToString, this)

        if (isConnected()) {
            log.atWarning()
                .withStackTrace(StackSize.MEDIUM)
                .log(
                    "Creating SimpleRedisCache '%s' after RedisApi is connected; initializing immediately (blocking). " +
                            "Consider creating caches before connecting to avoid this blocking call.",
                    namespace
                )

            runBlocking { initialize(cache) }
        } else {
            initializables.put(cache, Unit)
        }

        disposables.put(cache, Unit)
        return cache
    }

    /**
     * Creates a [SimpleSetRedisCache] for the given [namespace] using a serializer derived from `T`.
     *
     * Must be called before [freeze].
     *
     * @param namespace Prefix placed before each Redis key.
     * @param ttl Time-to-live for cache entries.
     * @param idOf Function that extracts a stable identifier for elements.
     * @param indexes Optional index configuration for the set cache.
     */
    inline fun <reified T : Any> createSimpleSetRedisCache(
        namespace: String,
        ttl: Duration,
        noinline idOf: (T) -> String,
        indexes: RedisSetIndexes<T> = RedisSetIndexes.empty()
    ): SimpleSetRedisCache<T> =
        createSimpleSetRedisCache(
            namespace,
            json.serializersModule.serializer(),
            ttl,
            idOf,
            indexes
        )


    /**
     * Creates a [SimpleSetRedisCache] for the given [namespace] using the provided [serializer].
     *
     * Must be called before [freeze].
     *
     * @param namespace Prefix placed before each Redis key.
     * @param serializer Serializer used for elements.
     * @param ttl Time-to-live for cache entries.
     * @param idOf Function that extracts a stable identifier for elements.
     * @param indexes Optional index configuration for the set cache.
     */
    fun <T : Any> createSimpleSetRedisCache(
        namespace: String,
        serializer: KSerializer<T>,
        ttl: Duration,
        idOf: (T) -> String,
        indexes: RedisSetIndexes<T> = RedisSetIndexes.empty()
    ): SimpleSetRedisCache<T> {
        val cache =
            RedisComponentProvider.createSimpleSetRedisCache(
                namespace,
                serializer,
                ttl,
                idOf,
                indexes,
                this
            )

        if (isConnected()) {
            log.atWarning()
                .withStackTrace(StackSize.MEDIUM)
                .log(
                    "Creating SimpleSetRedisCache '%s' after RedisApi is connected; initializing immediately (blocking). " +
                            "Consider creating caches before connecting to avoid this blocking call.",
                    namespace
                )
            runBlocking { initialize(cache) }
        } else {
            initializables.put(cache, Unit)
        }

        disposables.put(cache, Unit)
        return cache
    }
}
