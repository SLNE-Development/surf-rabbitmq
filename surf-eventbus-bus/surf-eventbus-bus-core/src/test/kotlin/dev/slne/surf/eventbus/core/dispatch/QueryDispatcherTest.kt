package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.core.FakeQueryTransport
import dev.slne.surf.eventbus.core.query.serialization.QuerySerializerCache
import dev.slne.surf.eventbus.core.registry.QueryServiceRegistry
import dev.slne.surf.eventbus.query.QueryService
import dev.slne.surf.eventbus.transport.QueryFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val reports = CopyOnWriteArrayList<AuditReport>()
    private val sink = object : AuditSink {
        override suspend fun report(report: AuditReport) {
            reports += report
        }
    }

    @Test
    fun `an answering handler sends exactly one answer`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Answering) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frameFor("ada"))

        assertEquals(1, transport.answered.size)
        assertEquals(0, reports.size)
    }

    @Test
    fun `an abstaining handler sends nothing`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Abstaining) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frameFor("ada"))

        assertEquals(0, transport.answered.size, "null means abstain: no message goes out")
        assertEquals(0, reports.size)
    }

    @Test
    fun `a throwing handler sends nothing and is audited`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Throwing) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frameFor("ada"))

        assertEquals(0, transport.answered.size, "an error answer would overtake a correct one")
        assertEquals(1, reports.size)
        assertEquals(AuditKind.QUERY_HANDLER_FAILED, reports.single().kind)
        assertTrue(reports.single().contract!!.endsWith("Locator"))
    }

    @Test
    fun `a frame for an unoffered contract is ignored`() = runBlocking {
        val transport = FakeQueryTransport()

        QueryDispatcher(QueryServiceRegistry(), "lobby-1", sink, json, transport).dispatch(frameFor("ada"))

        assertEquals(0, transport.answered.size)
        assertEquals(0, reports.size)
    }

    private fun frameFor(player: String): QueryFrame {
        val callable = LocatorDescriptor.getCallable("whereIs")!!
        val argumentsSerializer = QuerySerializerCache().getParameterSerializer(callable, json.serializersModule)
        val payload = json.encodeToString(argumentsSerializer, arrayOf(player))

        return QueryFrame(
            contract = Locator::class.java.name,
            callable = "whereIs",
            correlationId = "c-1",
            originInstanceId = "lobby-9",
            payload = payload
        )
    }
}

/**
 * `internal`, not `private`: a truly file-private interface cannot be referenced from the
 * generated descriptor (a separate file), so the KSP processor skips codegen for one entirely -
 * `QueryServiceRegistryTest`'s own private fixtures rely on exactly that skip. `internal` keeps
 * this contract out of the public API while still letting the generator produce `LocatorDescriptor`.
 */
@QueryService
internal interface Locator {
    suspend fun whereIs(player: String): String?
}

private object Answering : Locator {
    override suspend fun whereIs(player: String): String? = "lobby-1"
}

private object Abstaining : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object Throwing : Locator {
    override suspend fun whereIs(player: String): String? = error("handler is broken")
}
