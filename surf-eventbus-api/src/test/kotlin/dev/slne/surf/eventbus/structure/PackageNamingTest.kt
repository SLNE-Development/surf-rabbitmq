package dev.slne.surf.eventbus.structure

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

    private val repositoryRoot: java.nio.file.Path =
        generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
            .toPath()


    private val allowedPrefix = "package dev.slne.surf.eventbus"

    @Test
    fun `every source file declares a package under dev slne surf eventbus`() {
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
