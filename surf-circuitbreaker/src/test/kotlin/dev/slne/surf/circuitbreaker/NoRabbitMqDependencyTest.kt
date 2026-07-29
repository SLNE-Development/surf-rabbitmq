package dev.slne.surf.circuitbreaker

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * The module is meant to be reused unchanged from surf-broker and surf-redis. A single
 * RabbitMQ import would silently destroy that, and nothing else in the build would notice.
 */
class NoRabbitMqDependencyTest {

    private val forbidden = listOf(
        "com.rabbitmq",
        "dev.slne.surf.rabbitmq"
    )

    @Test
    fun `no source file references RabbitMQ`() {
        val sourceRoot = Path.of("src", "main", "kotlin")

        if (!Files.exists(sourceRoot)) {
            fail(
                "Expected sources at ${sourceRoot.toAbsolutePath()}. " +
                        "This test must run with the module directory as working directory."
            )
        }

        val offenders = Files.walk(sourceRoot).asSequence()
            .filter { it.extension == "kt" }
            .mapNotNull { file ->
                val hit = forbidden.firstOrNull { file.readText().contains(it) }
                hit?.let { "$file references '$it'" }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "surf-circuitbreaker must stay free of RabbitMQ so it can be reused " +
                        "from surf-broker and surf-redis:\n" + offenders.joinToString("\n")
            )
        }
    }
}
