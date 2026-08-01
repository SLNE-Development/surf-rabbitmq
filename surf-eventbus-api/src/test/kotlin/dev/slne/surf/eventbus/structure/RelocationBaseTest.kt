package dev.slne.surf.eventbus.structure

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Netty relocation base must not contain "lib" and must exist exactly once.
 *
 * A base containing "lib" makes Netty's native loader fail to find its mangled resources, and
 * the failure surfaces at runtime on a real server rather than in the build. Two bases mean two
 * Netty copies in one jar.
 */
class RelocationBaseTest {

    private val rootBuildFile = Path.of("..", "build.gradle.kts")

    @Test
    fun `the netty relocation base is declared once and contains no lib`() {
        val text = rootBuildFile.readText()
        val declarations = Regex("""val nettyBase = "([^"]+)"""").findAll(text).toList()

        assertEquals(1, declarations.size, "expected exactly one Netty relocation base")

        val base = declarations.single().groupValues[1]
        assertTrue(base.startsWith("dev.slne.surf.eventbus.shaded."), "unexpected base: $base")
        assertFalse(base.contains("lib"), "a base containing 'lib' breaks Netty's native loader")
    }

    @Test
    fun `no module declares its own netty relocation`() {
        val rootDir = Path.of("..").toAbsolutePath().normalize()
        val rootBuild = rootDir.resolve("build.gradle.kts")

        val offenders = rootDir.toFile()
            .walkTopDown()
            .filter { it.name == "build.gradle.kts" && it.toPath().normalize() != rootBuild }
            .filterNot { it.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
            .filter { it.readText().contains("relocate(\"io.netty\"") }
            .map { it.path }
            .toList()

        assertTrue(offenders.isEmpty(), "Netty relocated outside the root build: $offenders")
    }
}
