package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One processor reads both annotations.
 *
 * Two providers meant two resolver walks over the same file and two copies of every helper.
 * They also meant a consumer's `ksp(...)` line silently attached two processors, which is what
 * the KSP2 lifetime bug in the test doubles is downstream of.
 */
class OneProcessorTest {

    @Test
    fun `exactly one processor provider is registered`() {
        val registration = checkNotNull(
            javaClass.classLoader.getResource(
                "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
            )
        ) { "the processor is not registered at all" }

        val entries = registration.readText()
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

        assertEquals(
            listOf("dev.slne.surf.eventbus.ksp.ServiceProcessorProvider"),
            entries
        )
    }

    @Test
    fun `both contract kinds compile in one pass over one file`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService
            import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService

            @QueryService
            interface Asks {
                suspend fun whoOwns(id: String): String?
            }

            @RpcService(service = "svc")
            interface Calls {
                suspend fun rename(id: String, name: String)
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }
}
