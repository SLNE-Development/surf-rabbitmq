package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.SurfEventBus
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Regression test for C8.
 *
 * `SurfEventBusBuilder.build(environment)` declared the parameter and the only implementation
 * never read it - a public API promise ("you can inject the environment") that did nothing,
 * while the resolver it should have fed accepts exactly such an override. Wiring it also gives
 * the config layering the test seam it otherwise lacks.
 */
class BuildEnvironmentOverrideTest {

    private val dataPath = Files.createTempDirectory("bus-environment")

    @Test
    fun `build(environment) overrides the resolved rabbit settings`() {
        val bus = SurfEventBus.builder("surf-test", dataPath)
            .instanceName("lobby-1")
            .withRabbit()
            .build(
                mapOf(
                    "SURF_EVENTBUS_RABBITMQ_HOST" to "broker.example",
                    "SURF_EVENTBUS_RABBITMQ_PORT" to "5673",
                    "SURF_EVENTBUS_RABBITMQ_VHOST" to "/injected",
                )
            )

        val config = bus.rabbit.config
        assertEquals("broker.example", config.host)
        assertEquals(5673, config.port)
        assertEquals("/injected", config.vhost)
    }

    @Test
    fun `build() without an environment still resolves the built-in defaults`() {
        val bus = SurfEventBus.builder("surf-test", dataPath)
            .instanceName("lobby-2")
            .withRabbit()
            .build()

        // Whatever the defaults are, the injected host above must not leak into a bus that
        // asked for no override - the resolution is per build(), not process-wide.
        assertEquals(false, bus.rabbit.config.host == "broker.example")
    }
}
