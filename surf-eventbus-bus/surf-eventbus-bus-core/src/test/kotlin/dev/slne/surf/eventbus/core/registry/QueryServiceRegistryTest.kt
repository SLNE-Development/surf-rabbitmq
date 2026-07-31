package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.query.QueryService
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class QueryServiceRegistryTest {

    @Test
    fun `a registered contract is offered under its fully qualified name`() {
        val registry = QueryServiceRegistry()
        registry.register(Locator::class.java, LocatorImpl)

        assertEquals(setOf(Locator::class.java.name), registry.contracts())
        assertSame(LocatorImpl, registry.implementationOf(Locator::class.java.name))
    }

    @Test
    fun `a contract without the annotation is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QueryServiceRegistry().register(Unannotated::class.java, UnannotatedImpl)
        }

        assertContains(failure.message!!, "@QueryService")
    }

    @Test
    fun `two implementations of the same contract in one process are rejected`() {
        val registry = QueryServiceRegistry()
        registry.register(Locator::class.java, LocatorImpl)

        val failure = assertFailsWith<IllegalStateException> {
            registry.register(Locator::class.java, OtherLocatorImpl)
        }

        assertContains(failure.message!!, Locator::class.java.name)
    }

    @Test
    fun `an implementation that does not implement the contract is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QueryServiceRegistry().register(Locator::class.java, "not a locator")
        }

        assertContains(failure.message!!, "does not implement")
    }

    @Test
    fun `registration after freeze is rejected`() {
        val registry = QueryServiceRegistry()
        registry.freeze()

        assertFailsWith<IllegalStateException> { registry.register(Locator::class.java, LocatorImpl) }
    }

    @Test
    fun `the timeout of the contract is readable`() {
        assertEquals(2_000L, QueryServiceRegistry().timeoutOf(FastLocator::class.java))
        assertEquals(5_000L, QueryServiceRegistry().timeoutOf(Locator::class.java))
    }
}

@QueryService
private interface Locator {
    suspend fun whereIs(player: String): String?
}

@QueryService(timeoutMillis = 2_000)
private interface FastLocator {
    suspend fun whereIs(player: String): String?
}

private interface Unannotated {
    suspend fun whereIs(player: String): String?
}

private object LocatorImpl : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object OtherLocatorImpl : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object UnannotatedImpl : Unannotated {
    override suspend fun whereIs(player: String): String? = null
}
