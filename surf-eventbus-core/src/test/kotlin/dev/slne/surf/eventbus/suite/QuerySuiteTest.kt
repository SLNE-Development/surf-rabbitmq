package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.query
import dev.slne.surf.eventbus.query.QueryService
import dev.slne.surf.eventbus.registerService
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Spec tests 13–23: queries over a real Redis.
 *
 * A query is a broadcast question, so the interesting cases all need more than one process:
 * who answers, who abstains, and what the asker sees when nobody does. That is exactly what
 * [dev.slne.surf.eventbus.core.query.QueryServiceClientTest] cannot show — it runs one process
 * against a fake transport.
 *
 * **21 and 22 are not here.** They are compile-time failures, so they live where a compiler can
 * be run against them: `QueryServiceValidationTest` in `surf-eventbus-ksp`.
 */
@RequiresDocker
class QuerySuiteTest : RedisBusSuite() {

    @Test
    fun `13 - one of three providers answers and the asker gets that answer`() = runBlocking {
        connectedBus("suite-query-answering") { it.registerService<OwnerQuery>(AnsweringOwner("lobby-3")) }
        repeat(2) { index ->
            connectedBus("suite-query-abstain-$index") { it.registerService<OwnerQuery>(AbstainingOwner) }
        }

        val asker = connectedBus("suite-query-asker")

        assertEquals("lobby-3", asker.query<OwnerQuery>().ownerOf("world-1"))
    }

    @Test
    fun `14 - when two answer the first wins and the second is discarded`() = runBlocking {
        connectedBus("suite-query-fast") { it.registerService<OwnerQuery>(AnsweringOwner("fast")) }
        connectedBus("suite-query-slow") {
            it.registerService<OwnerQuery>(SlowOwner("slow", delayMillis = 400))
        }

        val asker = connectedBus("suite-query-asker-2")

        assertEquals(
            "fast",
            asker.query<OwnerQuery>().ownerOf("world-1"),
            "the first answer wins; the late one is dropped, not an error"
        )

        // The late answer arrives after the asker moved on and must not fail anything.
        delay(600)
    }

    @Test
    fun `15 - when everyone abstains the asker gets null after the timeout`() = runBlocking {
        repeat(2) { index ->
            connectedBus("suite-query-allabstain-$index") {
                it.registerService<OwnerQuery>(AbstainingOwner)
            }
        }

        val asker = connectedBus("suite-query-asker-3")

        assertNull(
            asker.query<OwnerQuery>().ownerOf("world-1"),
            "abstention is a normal outcome, not an exception"
        )
    }

    @Test
    fun `16 - with no provider at all the asker gets null after the timeout`() = runBlocking {
        val asker = connectedBus("suite-query-asker-4")

        assertNull(asker.query<OwnerQuery>().ownerOf("world-1"))
    }

    @Test
    fun `17 - a throwing handler does not stop a correct answer from another process`() = runBlocking {
        connectedBus("suite-query-throwing") { it.registerService<OwnerQuery>(ThrowingOwner) }
        connectedBus("suite-query-correct") { it.registerService<OwnerQuery>(AnsweringOwner("correct")) }

        val asker = connectedBus("suite-query-asker-5")

        assertEquals("correct", asker.query<OwnerQuery>().ownerOf("world-1"))
    }

    @Test
    fun `18 - a throwing handler with nobody else answering yields null`() = runBlocking {
        connectedBus("suite-query-throwing-only") { it.registerService<OwnerQuery>(ThrowingOwner) }

        val asker = connectedBus("suite-query-asker-6")

        assertNull(
            asker.query<OwnerQuery>().ownerOf("world-1"),
            "a broken handler looks exactly like abstention to the asker"
        )
    }

    @Test
    fun `19 - a process without the contract never sees the channel`() = runBlocking {
        val seen = AtomicInteger()

        connectedBus("suite-query-unrelated") {
            it.registerService<UnrelatedQuery>(CountingUnrelated(seen))
        }
        connectedBus("suite-query-provider") { it.registerService<OwnerQuery>(AnsweringOwner("owner")) }

        val asker = connectedBus("suite-query-asker-7")
        asker.query<OwnerQuery>().ownerOf("world-1")

        assertEquals(0, seen.get(), "a contract is its own channel; an unrelated one is not read")
    }

    @Test
    fun `23 - two providers of the same contract in one process fail at registration`() = runBlocking {
        val bus = connectedBus("suite-query-duplicate") {
            it.registerService(OwnerQuery::class, AnsweringOwner("first"))
        }

        assertFailsWith<IllegalStateException> {
            bus.registerService(OwnerQuery::class, AnsweringOwner("second"))
        }
        Unit
    }
}

@QueryService(timeoutMillis = 2_000)
interface OwnerQuery {
    suspend fun ownerOf(world: String): String?
}

@QueryService(timeoutMillis = 2_000)
interface UnrelatedQuery {
    suspend fun somethingElse(id: String): String?
}

private class AnsweringOwner(private val answer: String) : OwnerQuery {
    override suspend fun ownerOf(world: String): String = answer
}

private class SlowOwner(private val answer: String, private val delayMillis: Long) : OwnerQuery {
    override suspend fun ownerOf(world: String): String {
        delay(delayMillis)
        return answer
    }
}

private object AbstainingOwner : OwnerQuery {
    // null means abstain: not "no", and not an answer.
    override suspend fun ownerOf(world: String): String? = null
}

private object ThrowingOwner : OwnerQuery {
    override suspend fun ownerOf(world: String): String = error("this handler always fails")
}

private class CountingUnrelated(private val counter: AtomicInteger) : UnrelatedQuery {
    override suspend fun somethingElse(id: String): String? {
        counter.incrementAndGet()
        return null
    }
}
