package dev.slne.surf.eventbus.common

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * The foundation stays free of both brokers.
 *
 * Successor of surf-rabbitmq's SharedPackagePurityTest and surf-circuitbreaker's
 * NoRabbitMqDependencyTest, widened to Redisson. One broker import here would turn every later
 * module boundary into a suggestion.
 */
class CommonPurityTest {

    private val forbidden = listOf(
        "com.rabbitmq",
        "RabbitPacket",
        "RabbitClient",
        "org.redisson",
        "RedissonClient"
    )

    private val brokerFreeModules = listOf(
        Path.of("src", "main", "kotlin"),
        Path.of("..", "surf-eventbus-bus", "surf-eventbus-bus-api", "src", "main", "kotlin"),
        Path.of("..", "surf-eventbus-bus", "surf-eventbus-bus-core", "src", "main", "kotlin")
    )

    @Test
    fun `surf-eventbus-common references neither broker`() {
        val roots = brokerFreeModules.filter { Files.exists(it) }
        if (roots.isEmpty()) {
            fail("Expected sources at at least one of ${brokerFreeModules.map { it.toAbsolutePath() }}")
        }

        val offenders = roots.flatMap { root ->
            Files.walk(root).asSequence()
                .filter { it.extension == "kt" }
                .mapNotNull { file ->
                    val text = file.readText()
                    forbidden.firstOrNull { text.contains(it) }?.let { "$file references '$it'" }
                }
                .toList()
        }

        if (offenders.isNotEmpty()) {
            fail("Broker types in a bus-free module:\n" + offenders.joinToString("\n"))
        }
    }
}
