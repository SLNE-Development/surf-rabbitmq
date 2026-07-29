package dev.slne.surf.rabbitmq.shared

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * Keeps the broker-neutral packages extractable into `surf-broker`.
 *
 * These packages are shared with surf-redis later. A single RabbitMQ import would turn that
 * move from mechanical into a redesign, and nothing else in the build would notice.
 */
class SharedPackagePurityTest {

    private val neutralPackages = listOf(
        "shared/serialization",
        "shared/dispatch",
        "shared/config",
        "platform"
    )

    private val forbidden = listOf("com.rabbitmq", "RabbitMQ", "RabbitPacket", "RabbitClient")

    @Test
    fun `broker-neutral packages do not reference RabbitMQ`() {
        val root = Path.of("src", "main", "kotlin", "dev", "slne", "surf", "rabbitmq")

        if (!Files.exists(root)) {
            fail("Expected sources at ${root.toAbsolutePath()}")
        }

        val offenders = neutralPackages
            .map(root::resolve)
            .filter(Files::exists)
            .flatMap { dir ->
                Files.walk(dir).asSequence()
                    .filter { it.extension == "kt" }
                    .mapNotNull { file ->
                        val text = file.readText()
                        forbidden.firstOrNull { text.contains(it) }
                            ?.let { "$file references '$it'" }
                    }
                    .toList()
            }

        if (offenders.isNotEmpty()) {
            fail(
                "These packages must stay broker-neutral so they can move to surf-broker " +
                        "unchanged:\n" + offenders.joinToString("\n")
            )
        }
    }
}
