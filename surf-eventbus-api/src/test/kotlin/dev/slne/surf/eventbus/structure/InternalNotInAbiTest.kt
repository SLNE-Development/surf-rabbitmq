package dev.slne.surf.eventbus.structure

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Nothing internal reaches the published ABI.
 *
 * The test this replaces asserted that classes of this module are on this module's classpath,
 * which cannot fail. The question worth asking is the opposite one: does a declaration marked
 * `@InternalEventBusApi` leak into what consumers compile against?
 */
class InternalNotInAbiTest {

    private val repositoryRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `no internal package appears in the abi dump`() {
        val dump = File(repositoryRoot, "surf-eventbus-api/api/surf-eventbus-api.api")
        assertTrue(dump.isFile, "run ./gradlew updateLegacyAbi first")

        // Packages whose every declaration is @InternalEventBusApi. A name showing up here
        // means the annotation was forgotten, not that the filter is wrong.
        val internalRoots = listOf(
            "dev/slne/surf/eventbus/service/",
            "dev/slne/surf/eventbus/config/",
        )

        // Declaration lines only. A *reference* to an internal type from a public signature is
        // a different thing and a legitimate one: SurfRabbitApiBuilder.config(RabbitMQSettings)
        // and RabbitCredentialsProvider.credentials(...) are the two seams that exist precisely
        // to take one, and a consumer still cannot call either without opting in.
        val offenders = dump.readLines()
            .filter { it.contains(" class ") && !it.startsWith("\t") }
            .filter { line ->
                val declared = line.substringAfter(" class ").substringBefore(" ")
                internalRoots.any { declared.startsWith(it) }
            }

        assertTrue(offenders.isEmpty(), "internal declarations in the ABI:\n$offenders")
    }
}
