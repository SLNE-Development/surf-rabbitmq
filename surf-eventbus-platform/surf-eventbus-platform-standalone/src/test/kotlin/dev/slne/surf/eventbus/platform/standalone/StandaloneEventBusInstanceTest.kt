package dev.slne.surf.eventbus.platform.standalone

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * One instance serves both transports.
 *
 * There were two per-transport instances and no platform implemented both: Paper and Velocity
 * shipped a RabbitMQ instance and no Redis one, so `publish` and `query` — the two verbs that
 * ride Redis — could not work there at all.
 */
class StandaloneEventBusInstanceTest {

    @Test
    fun `the data path reaches the instance`() {
        val dataPath = Files.createTempDirectory("standalone")
        val instance = StandaloneEventBusInstance(dataPath)

        assertEquals(dataPath, instance.dataPath)
    }

    @Test
    fun `the plugin name of any caller is the service name`() {
        val instance = StandaloneEventBusInstance(Files.createTempDirectory("standalone"))

        // A standalone process has no plugins, so attribution is always the process itself.
        assertEquals("surf-eventbus-standalone", instance.tryExtractPluginName(String::class.java))
    }

    @Test
    fun `configure is what the ServiceLoader instance reads its path from`() {
        val dataPath = Files.createTempDirectory("standalone")
        StandaloneEventBusInstance.configure("surf-audit", dataPath)

        // The no-arg constructor is the one ServiceLoader calls.
        val instance = StandaloneEventBusInstance()

        assertEquals(dataPath, instance.dataPath)
        assertEquals("surf-audit", instance.tryExtractPluginName(String::class.java))
    }
}
