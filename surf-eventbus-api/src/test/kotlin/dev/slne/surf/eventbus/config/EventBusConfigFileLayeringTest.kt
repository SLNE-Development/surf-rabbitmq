package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * Which *file* each layer is read from — the half of the layering that lives outside
 * [resolveEventBusConfig].
 *
 * [EventBusConfigLayeringTest] covers the merge given the layers; this covers the question that
 * was actually answered wrongly. The Redis half passed the platform plugin's folder as both the
 * global and the plugin layer, so the `eventbus-plugin.yml` it opened lived next to
 * `eventbus.yml` in a folder no consumer owns, and nothing a plugin wrote ever reached Redis —
 * while the identical RabbitMQ path worked. Both transports now resolve through one function,
 * so a case here is a case for both.
 */
class EventBusConfigFileLayeringTest {

    private val noEnvironment = EnvironmentVariables.from(emptyMap())

    private fun Path.writeConfig(fileName: String, body: String): Path = apply {
        createDirectories()
        resolve(fileName).writeText(body)
    }

    @Test
    fun `a plugin-local eventbus-plugin yml overrides redis host`(@TempDir root: Path) {
        val platform = root.resolve("platform").writeConfig(
            EventBusConfigFiles.GLOBAL_FILE_NAME,
            """
            redis:
                host: redis-global
            """.trimIndent()
        )
        val plugin = root.resolve("plugin").writeConfig(
            EventBusConfigFiles.PLUGIN_FILE_NAME,
            """
            redis:
                host: redis-plugin
            """.trimIndent()
        )

        val resolved = resolveEventBusSettings(
            pluginDataPath = plugin,
            platformDataPath = platform,
            environment = noEnvironment,
        )

        assertEquals("redis-plugin", resolved.redis.host)
    }

    @Test
    fun `the plugin file is read from the consumer folder, not the platform folder`(
        @TempDir root: Path
    ) {
        // The regression itself: only the *consumer's* folder has a plugin file. Reading the
        // plugin layer out of the platform folder finds nothing here and silently returns the
        // global value, which is exactly what Redis used to do.
        val platform = root.resolve("platform").writeConfig(
            EventBusConfigFiles.GLOBAL_FILE_NAME,
            """
            redis:
                host: redis-global
                port: 6380
            """.trimIndent()
        )
        val plugin = root.resolve("plugin").writeConfig(
            EventBusConfigFiles.PLUGIN_FILE_NAME,
            """
            redis:
                host: redis-plugin
            """.trimIndent()
        )

        val resolved = resolveEventBusSettings(
            pluginDataPath = plugin,
            platformDataPath = platform,
            environment = noEnvironment,
        )

        assertEquals("redis-plugin", resolved.redis.host)
        // Untouched by the plugin file, so the global still wins over the built-in default —
        // per field, not per file.
        assertEquals(6380, resolved.redis.port)
    }

    @Test
    fun `rabbitmq resolves from the same two files`(@TempDir root: Path) {
        val platform = root.resolve("platform").writeConfig(
            EventBusConfigFiles.GLOBAL_FILE_NAME,
            """
            rabbitmq:
                host: rabbit-global
            """.trimIndent()
        )
        val plugin = root.resolve("plugin").writeConfig(
            EventBusConfigFiles.PLUGIN_FILE_NAME,
            """
            rabbitmq:
                host: rabbit-plugin
            """.trimIndent()
        )

        val resolved = resolveEventBusSettings(
            pluginDataPath = plugin,
            platformDataPath = platform,
            environment = noEnvironment,
        )

        assertEquals("rabbit-plugin", resolved.rabbitmq.host)
    }

    @Test
    fun `standalone reads its global file from the one folder it was given`(@TempDir root: Path) {
        val data = root.resolve("standalone").writeConfig(
            EventBusConfigFiles.GLOBAL_FILE_NAME,
            """
            redis:
                host: redis-standalone
            """.trimIndent()
        )

        val resolved = resolveEventBusSettings(
            pluginDataPath = data,
            platformDataPath = null,
            environment = noEnvironment,
        )

        assertEquals("redis-standalone", resolved.redis.host)
    }

    @Test
    fun `the environment still beats both files`(@TempDir root: Path) {
        val platform = root.resolve("platform").writeConfig(
            EventBusConfigFiles.GLOBAL_FILE_NAME,
            """
            redis:
                host: redis-global
            """.trimIndent()
        )
        val plugin = root.resolve("plugin").writeConfig(
            EventBusConfigFiles.PLUGIN_FILE_NAME,
            """
            redis:
                host: redis-plugin
            """.trimIndent()
        )

        val resolved = resolveEventBusSettings(
            pluginDataPath = plugin,
            platformDataPath = platform,
            environment = EnvironmentVariables.from(
                mapOf("SURF_EVENTBUS_REDIS_HOST" to "redis-env")
            ),
        )

        assertEquals("redis-env", resolved.redis.host)
    }
}
