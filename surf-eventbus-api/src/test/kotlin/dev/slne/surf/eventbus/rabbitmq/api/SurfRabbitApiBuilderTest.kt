package dev.slne.surf.eventbus.rabbitmq.api

import dev.slne.surf.eventbus.rabbitmq.api.internal.config.CommonRabbitMQConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SurfRabbitApiBuilderTest {

    private val dataPath = Files.createTempDirectory("surf-rabbit-test")

    // Builder tests always inject a config stub. Default config loading touches the
    // filesystem and, standalone, the StandaloneLifecycleHook - both are exercised by the
    // integration tests in core, not here.
    private fun stubConfig(): CommonRabbitMQConfig = object : CommonRabbitMQConfig {
        override fun getHost() = "localhost"
        override fun getPort() = 5672
        override fun getUsername() = "guest"
        override fun getPassword() = "guest"
        override fun getVhost() = "/"
        override fun getTimeout() = 5
        override fun getRequestTimeoutSeconds() = 5
        override fun getPublisherPoolSize() = 1
        override fun getServerPrefetchCount() = 1
        override fun isPersistRequests() = false
        override fun isPersistResponses() = false
        override fun isOutgoingRequestChunkingEnabled() = false
        override fun isOutgoingResponseChunkingEnabled() = false
    }

    private fun builder(serviceName: String) =
        SurfRabbitApi.builder(serviceName, dataPath).config(stubConfig())

    @Test
    fun `the builder derives an identity from the service name`() {
        val api = builder("surf-factions").build()

        assertEquals("surf-factions", api.identity.serviceName)
        assertTrue(api.identity.instanceId.startsWith("surf-factions-"))
    }

    @Test
    fun `an explicit instance name overrides the random suffix`() {
        val api = SurfRabbitApi.builder("lobby", dataPath)
            .config(stubConfig())
            .instanceName("lobby-3")
            .build()

        assertEquals("lobby-3", api.identity.instanceId)
    }

    @Test
    fun `a blank service name is rejected at build time`() {
        assertFailsWith<IllegalArgumentException> {
            builder("  ").build()
        }
    }

    @Test
    fun `a fresh api is not frozen`() {
        val api = builder("svc").build()
        assertTrue(!api.isFrozen())
    }

    @Test
    fun `freezing twice is refused`() {
        val api = builder("svc").build()
        api.freeze()

        assertFailsWith<IllegalStateException> { api.freeze() }
    }

    @Test
    fun `a service cannot be registered after freezing`() {
        val api = builder("svc").build()
        api.freeze()

        assertFailsWith<IllegalStateException> {
            api.registerService(Any::class, Any())
        }
    }

    @Test
    fun `two instances of the same service get different identities`() {
        val a = builder("svc").build()
        val b = builder("svc").build()

        assertTrue(
            a.identity.instanceId != b.identity.instanceId,
            "identical instance ids would make two processes share a reply queue"
        )
    }
}
