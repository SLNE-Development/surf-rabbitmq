package dev.slne.surf.eventbus.common

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * Every Kotlin file in the repository declares a package under `dev.slne.surf.eventbus`.
 *
 * A half-finished rename compiles: the old package still exists, imports still resolve, and
 * nothing fails until a consumer wonders why one class sits somewhere else. This test is the
 * only thing that notices.
 */
class PackageNamingTest {

    private val allowedPrefix = "package dev.slne.surf.eventbus"

    @Test
    fun `every source file declares a package under dev slne surf eventbus`() {
        val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
        val offenders = Files.walk(repositoryRoot).asSequence()
            .filter { it.extension == "kt" }
            .filterNot { it.toString().contains("${java.io.File.separator}build${java.io.File.separator}") }
            .mapNotNull { file ->
                val declaration = file.readText()
                    .lineSequence()
                    .firstOrNull { it.startsWith("package ") }
                    ?: return@mapNotNull null

                if (declaration.startsWith(allowedPrefix)) null else "$file declares '$declaration'"
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Files outside dev.slne.surf.eventbus:\n" + offenders.joinToString("\n"))
        }
    }
}
