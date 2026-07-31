package dev.slne.surf.eventbus.common.circuitbreaker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test
    fun `test infrastructure runs and can fail`() {
        assertEquals(1, 1, "test infrastructure works")
    }
}
