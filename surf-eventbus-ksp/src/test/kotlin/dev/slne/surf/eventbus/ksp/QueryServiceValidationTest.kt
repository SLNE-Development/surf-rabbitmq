package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class QueryServiceValidationTest {

    @Test
    fun `a non-nullable return type fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                suspend fun whereIs(player: String): String
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "nullable")
        assertContains(result.messages, "abstain")
    }

    @Test
    fun `a Unit return type fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                suspend fun notify(player: String)
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "event")
    }

    @Test
    fun `fire-and-forget on a query method fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService
            import dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget

            @QueryService
            interface Broken {
                @FireAndForget
                suspend fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "@FireAndForget")
    }

    @Test
    fun `a nullable suspend method compiles`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService(timeoutMillis = 2_000)
            interface Fine {
                suspend fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(true, result.succeeded, result.messages)
    }

    @Test
    fun `a non-suspend method fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "suspend")
    }
}
