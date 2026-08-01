package dev.slne.surf.eventbus.core.query

import dev.slne.surf.eventbus.core.FakeQueryTransport
import dev.slne.surf.eventbus.query.QueryService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@QueryService(timeoutMillis = 1_000)
interface LocatorQuery {
    suspend fun whereIs(player: String): String?
}

/**
 * The KSP-generated client proxy for `@QueryService`, exercised against a fake transport.
 *
 * Server-side dispatch (turning a registered implementation into answers) arrives in Plan 3
 * Task 7 with the real `RedisQueryTransport`; this covers the client half the descriptor
 * generator is responsible for.
 */
class QueryServiceClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the generated client asks and decodes the answer`() = runBlocking {
        val transport = FakeQueryTransport()
        transport.nextAnswer = json.encodeToString(String.serializer(), "lobby-1")

        val client = LocatorQueryDescriptor.createInstance("caller-1", json, transport)

        assertEquals("lobby-1", client.whereIs("ada"))
        assertEquals(1, transport.asked.size)

        val frame = transport.asked.single()
        assertEquals(LocatorQueryDescriptor.fqName, frame.contract)
        assertEquals("whereIs", frame.callable)
        assertEquals("caller-1", frame.originInstanceId)
    }

    @Test
    fun `no answer means abstain`() = runBlocking {
        val transport = FakeQueryTransport()
        val client = LocatorQueryDescriptor.createInstance("caller-1", json, transport)

        assertNull(client.whereIs("ada"))
    }

    @Test
    fun `the annotation's timeout lands in the generated descriptor`() {
        assertEquals(1_000L, LocatorQueryDescriptor.timeoutMillis)
    }
}
