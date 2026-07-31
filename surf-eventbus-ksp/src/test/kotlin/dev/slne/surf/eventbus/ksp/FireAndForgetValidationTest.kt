package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The processor rejects a @FireAndForget method that promises a value.
 *
 * Compile-time is the only place this can be caught: at runtime the caller would wait for an
 * answer that the server was told not to send.
 */
class FireAndForgetValidationTest {

    @Test
    fun `a fire-and-forget method returning something other than Unit fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

            @RpcService(service = "svc")
            interface Broken {
                @FireAndForget
                suspend fun doWork(id: String): Boolean
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "@FireAndForget")
        assertContains(result.messages, "Unit")
    }

    @Test
    fun `a fire-and-forget method returning Unit compiles`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

            @RpcService(service = "svc")
            interface Fine {
                @FireAndForget
                suspend fun doWork(id: String)
            }
            """.trimIndent()
        )

        assertEquals(true, result.succeeded, result.messages)
    }
}
