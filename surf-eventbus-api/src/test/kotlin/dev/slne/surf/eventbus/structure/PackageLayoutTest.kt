package dev.slne.surf.eventbus.structure

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The package layout the merge was supposed to produce.
 *
 * Three names survived the merge and describe the old module structure rather than the code:
 * `common` (a drawer, not a subject), `rabbitmq.api` (an `.api` infix Redis never had), and
 * kebab-case file names. Each of them compiles fine, which is exactly why only a test notices.
 */
class PackageLayoutTest {

    private val repositoryRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourceFiles(): List<File> = repositoryRoot.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .filterNot { it.path.contains("${File.separator}build${File.separator}") }
        .toList()

    @Test
    fun `no package is called common`() {
        val offenders = sourceFiles()
            .filter { it.path.contains("${File.separator}common${File.separator}") }
            .map { it.relativeTo(repositoryRoot).path }

        assertTrue(offenders.isEmpty(), "'common' names a drawer, not a subject:\n$offenders")
    }

    @Test
    fun `no package under rabbitmq is called api`() {
        val offenders = sourceFiles()
            .filter { it.path.contains("rabbitmq${File.separator}api${File.separator}") }
            .map { it.relativeTo(repositoryRoot).path }

        assertTrue(offenders.isEmpty(), "redis has no .api infix; rabbitmq must not either:\n$offenders")
    }

    @Test
    fun `every source file name is UpperCamelCase`() {
        val offenders = sourceFiles()
            .filterNot { it.nameWithoutExtension.matches(Regex("[A-Z][A-Za-z0-9]*")) }
            .map { it.relativeTo(repositoryRoot).path }

        if (offenders.isNotEmpty()) fail("not UpperCamelCase:\n" + offenders.joinToString("\n"))
    }
}
