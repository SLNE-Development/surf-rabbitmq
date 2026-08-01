package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.bus.RedisEventTransport
import dev.slne.surf.eventbus.redis.bus.RedisQueryTransport
import dev.slne.surf.eventbus.testing.RedisContainerExtension
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.AfterEach
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Base class for the container suites: builds buses on the test container and tears them down.
 *
 * `withRedis()` resolves its transports through `RedisTransportLocator`, which reads the
 * process-wide `redisConfig` — a `by lazy` fixed on first touch, and so unable to point at a
 * container from inside a test JVM. `withRedis(event, query)` is the seam that exists for
 * exactly this.
 *
 * [tearDown] is not optional. Every bus in a suite subscribes to the same channels on one
 * shared container, so a bus left connected by an earlier test answers a later test's query
 * and makes the failure look like a bug in the bus rather than in the harness.
 */
abstract class RedisBusSuite {

    private val buses = CopyOnWriteArrayList<SurfEventBus>()
    private val apis = CopyOnWriteArrayList<RedisApi>()

    /**
     * A connected bus with its own Redis connection, named [serviceName].
     *
     * Each call opens a separate connection so a suite can model several processes; that is
     * the point of every broadcast case in the spec.
     */
    protected suspend fun connectedBus(
        serviceName: String,
        serializers: SerializersModule = SerializersModule { },
        register: (SurfEventBus) -> Unit = {},
    ): SurfEventBus {
        val redis = RedisApi.create(
            redisURI = RedisContainerExtension.redisUri(),
            pluginName = serviceName,
            serializerModule = serializers,
        )
        apis += redis

        var connected = false
        val ensureConnected: suspend () -> Unit = {
            if (!connected) {
                if (!redis.isFrozen()) redis.freeze()
                redis.connect()
                connected = true
            }
        }

        val bus = SurfEventBus.builder(serviceName, dataPath)
            .serializers(serializers)
            .withRedis(
                RedisEventTransport(redis, redis.json, ensureConnected),
                RedisQueryTransport(redis, redis.json, ensureConnected),
            )
            .build()

        buses += bus
        register(bus)
        bus.freezeAndConnect()
        return bus
    }

    @AfterEach
    fun tearDown() = runBlocking {
        buses.forEach { runCatching { it.disconnect() } }
        buses.clear()
        apis.forEach { runCatching { it.disconnect() } }
        apis.clear()
    }

    protected companion object {
        val dataPath: java.nio.file.Path = Files.createTempDirectory("surf-eventbus-suite")

        /** Pub/sub delivery is asynchronous and the suite has no completion signal to await. */
        const val SETTLE_MILLIS = 500L
    }
}
