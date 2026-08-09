package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.config.EventBusConfigFiles
import dev.slne.surf.eventbus.testing.RedisContainerExtension
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.AfterEach
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText

/**
 * Base class for the container suites: builds buses on the test container and tears them down.
 *
 * Buses are built through the production `withRedis()` path. They could not be until the Redis
 * settings became per-consumer: `withRedis()` resolved a process-wide value fixed on first
 * touch, so no test could point one at a container and every suite had to reach for the
 * `withRedis(event, query)` seam instead — which meant the wiring real callers use was the one
 * thing the Redis suites never exercised. Each bus gets its own data folder holding an
 * `eventbus.yml` that names the container, which is exactly what an operator writes.
 *
 * [tearDown] is not optional. Every bus in a suite subscribes to the same channels on one
 * shared container, so a bus left connected by an earlier test answers a later test's query
 * and makes the failure look like a bug in the bus rather than in the harness.
 */
abstract class RedisBusSuite {

    private val buses = CopyOnWriteArrayList<SurfEventBus>()

    /**
     * A connected bus with its own Redis connection, named [serviceName].
     *
     * Each call gets a fresh data folder, so each opens a separate connection and a suite can
     * model several processes; that is the point of every broadcast case in the spec.
     */
    protected suspend fun connectedBus(
        serviceName: String,
        serializers: SerializersModule = SerializersModule { },
        register: (SurfEventBus) -> Unit = {},
    ): SurfEventBus {
        val bus = SurfEventBus.builder(serviceName, containerDataPath(serviceName))
            .serializers(serializers)
            .withRedis()
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
    }

    protected companion object {
        val dataPath: Path = Files.createTempDirectory("surf-eventbus-suite")

        private val processCounter = AtomicInteger()

        /**
         * A data folder whose `eventbus.yml` points at the shared container.
         *
         * There is no platform registered in a test JVM, so this is read as the global layer —
         * the standalone shape, and the one branch of the resolution a suite can drive without
         * standing up a fake platform.
         */
        private fun containerDataPath(serviceName: String): Path {
            val host = RedisContainerExtension.host()
            val port = RedisContainerExtension.port()

            return Files.createDirectory(
                dataPath.resolve("$serviceName-${processCounter.incrementAndGet()}")
            ).also {
                it.resolve(EventBusConfigFiles.GLOBAL_FILE_NAME).writeText(
                    """
                    redis:
                        host: $host
                        port: $port
                    """.trimIndent()
                )
            }
        }

        /** Pub/sub delivery is asynchronous and the suite has no completion signal to await. */
        const val SETTLE_MILLIS = 500L
    }
}
