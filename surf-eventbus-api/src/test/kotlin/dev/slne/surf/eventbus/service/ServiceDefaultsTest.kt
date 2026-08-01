package dev.slne.surf.eventbus.service

import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one contract model both `@QueryService` and `@RpcService` descriptors are built from.
 *
 * Query used a bare `KType` and RPC used its own type wrapper, so a query parameter could not
 * carry `@Contextual` or `@Serializable(with = ...)`. Both now carry annotations, and a query
 * callable is simply one whose `fireAndForget` is false.
 */
class ServiceDefaultsTest {

    private object Marker

    @Test
    fun `a callable defaults to expecting an answer`() {
        val callable = ServiceCallableDefault<Marker>(
            name = "findName",
            returnType = ServiceTypeDefault(typeOf<String?>(), emptyList()),
            invoker = ServiceInvoker { _, _ -> null },
            parameters = emptyArray()
        )

        assertFalse(callable.fireAndForget, "only @FireAndForget opts out of a reply")
        assertEquals("findName", callable.name)
    }

    @Test
    fun `a parameter keeps its type annotations`() {
        val annotation = Deprecated("x")
        val parameter = ServiceParameterDefault(
            name = "id",
            type = ServiceTypeDefault(typeOf<String>(), listOf(annotation)),
            isOptional = true,
            annotations = emptyList()
        )

        assertEquals(listOf(annotation), parameter.type.annotations)
        assertTrue(parameter.isOptional)
    }

    @Test
    fun `an invoker forwards its arguments`() = kotlinx.coroutines.runBlocking {
        val invoker = ServiceInvoker<Marker> { _, arguments -> arguments[0] }

        assertEquals("a", invoker.call(Marker, arrayOf<Any?>("a")))
    }
}
