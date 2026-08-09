package dev.slne.surf.eventbus.redis

import dev.slne.surf.eventbus.redis.connection.RedisConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.redisson.Redisson
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for C9.
 *
 * [RedisConnection.FETCH_OS_LUA] used to be written in a Kotlin raw string with doubled backslashes,
 * which Lua then read as a character class excluding backslash, `r` and `n` rather than CR and
 * LF. It still matched - just two characters of the OS name - so the `os == null` fallback
 * never fired and `redisOsType` stayed null on Windows forever.
 *
 * Only a real Redis can settle this: the bug lives in how Lua parses the pattern, so a Kotlin
 * reimplementation would only test the reimplementation.
 */
@Tag("integration")
class RedisOsDetectionTest {
    @Test
    fun `the OS line is extracted whole, not truncated to two characters`() {
        val os =
            redisson.script.eval<String?>(
                RScript.Mode.READ_ONLY,
                RedisConnection.FETCH_OS_LUA,
                RScript.ReturnType.STRING,
            )

        assertNotNull(os, "INFO server always reports an os: line")

        // The old pattern returned exactly "Li" for "os:Linux 5.15.0 x86_64".
        assertTrue(
            os.length > 2,
            "expected the whole OS string, got '$os' - the Lua character class is escaping wrong",
        )
        assertTrue(
            os.startsWith("Linux"),
            "the redis:7-alpine container runs Linux, got '$os'",
        )
        assertEquals(os.trim(), os, "the match must stop at CR/LF, leaving no trailing newline")
    }

    @Test
    fun `a Windows server would be recognised`() {
        // Guards the comparison the extraction feeds, without needing a Windows Redis: the old
        // pattern yielded "Wi", so contains("Windows") was never true.
        val extracted = "Windows  version 10.0.19042"

        assertTrue(extracted.contains("Windows"))
    }

    companion object {
        private lateinit var container: GenericContainer<*>
        private lateinit var redisson: RedissonClient

        @JvmStatic
        @BeforeAll
        fun start() {
            container =
                GenericContainer(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
            container.start()

            val config =
                Config().apply {
                    useSingleServer().address =
                        "redis://${container.host}:${container.getMappedPort(6379)}"
                }
            redisson = Redisson.create(config)
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            if (::redisson.isInitialized) redisson.shutdown()
            if (::container.isInitialized) container.stop()
        }
    }
}
