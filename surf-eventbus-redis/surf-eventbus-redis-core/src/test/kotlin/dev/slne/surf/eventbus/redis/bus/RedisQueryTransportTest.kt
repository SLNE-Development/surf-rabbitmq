package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.testing.RequiresDocker
import dev.slne.surf.eventbus.transport.QueryFrame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.redisson.misc.RedisURI
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The query-suite cases that need a real broker: three providers with one answering, two
 * answering (first wins), all abstaining, no provider at all, and only the asker receiving the
 * reply.
 */
@RequiresDocker
class RedisQueryTransportTest {

    private fun newTransport(): RedisQueryTransport {
        val uri = RedisURI("redis://${redis.host}:${redis.getMappedPort(6379)}")
        val api = RedisApi.create(uri).freezeAndConnect()
        apis += api
        return RedisQueryTransport(api, api.json)
    }

    private fun frame(contract: String, instanceId: String, payload: String = "{}") = QueryFrame(
        contract = contract,
        callable = "whereIs",
        correlationId = UUID.randomUUID().toString(),
        originInstanceId = instanceId,
        payload = payload
    )

    @Test
    fun `one of three providers answers`() = runBlocking {
        val contract = "test.Locator.${UUID.randomUUID()}"

        newTransport().connect(setOf(contract), "provider-1") { f -> } // no answer
        newTransport().connect(setOf(contract), "provider-2") { f ->
            newTransport().answer(f, "\"lobby-2\"")
        }
        newTransport().connect(setOf(contract), "provider-3") { f -> } // no answer

        val asker = newTransport()
        asker.connect(emptySet(), "asker-1") {}

        val answer = asker.ask(frame(contract, "asker-1"), timeoutMillis = 5_000)
        assertEquals("\"lobby-2\"", answer)
    }

    @Test
    fun `two answering providers - the first reply wins`() = runBlocking {
        val contract = "test.Locator.${UUID.randomUUID()}"

        newTransport().connect(setOf(contract), "provider-1") { f ->
            newTransport().answer(f, "\"first\"")
        }
        newTransport().connect(setOf(contract), "provider-2") { f ->
            newTransport().answer(f, "\"second\"")
        }

        val asker = newTransport()
        asker.connect(emptySet(), "asker-1") {}

        val answer = asker.ask(frame(contract, "asker-1"), timeoutMillis = 5_000)
        assertEquals(true, answer == "\"first\"" || answer == "\"second\"")
    }

    @Test
    fun `nobody answers means null after the timeout`() = runBlocking {
        val contract = "test.Locator.${UUID.randomUUID()}"

        newTransport().connect(setOf(contract), "provider-1") { }

        val asker = newTransport()
        asker.connect(emptySet(), "asker-1") {}

        assertNull(asker.ask(frame(contract, "asker-1"), timeoutMillis = 1_000))
    }

    @Test
    fun `no provider at all means null`() = runBlocking {
        val contract = "test.Locator.${UUID.randomUUID()}"
        val asker = newTransport()
        asker.connect(emptySet(), "asker-1") {}

        assertNull(asker.ask(frame(contract, "asker-1"), timeoutMillis = 1_000))
    }

    @Test
    fun `only the asker receives the answer`() = runBlocking {
        val contract = "test.Locator.${UUID.randomUUID()}"
        val bystanderReceived = java.util.concurrent.atomic.AtomicBoolean(false)

        newTransport().connect(setOf(contract), "provider-1") { f ->
            newTransport().answer(f, "\"lobby-1\"")
        }

        val bystander = newTransport()
        bystander.connect(emptySet(), "bystander-1") {}

        val asker = newTransport()
        asker.connect(emptySet(), "asker-1") {}

        val answer = asker.ask(frame(contract, "asker-1"), timeoutMillis = 5_000)
        assertEquals("\"lobby-1\"", answer)
        assertEquals(false, bystanderReceived.get())
    }

    companion object {
        private lateinit var redis: GenericContainer<*>
        private val apis = java.util.concurrent.CopyOnWriteArrayList<RedisApi>()

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
            redis.start()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            apis.forEach { runCatching { it.disconnect() } }
            redis.stop()
        }
    }
}
