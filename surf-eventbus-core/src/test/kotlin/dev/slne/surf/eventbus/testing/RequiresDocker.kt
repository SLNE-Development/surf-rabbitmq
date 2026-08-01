package dev.slne.surf.eventbus.testing

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory

/**
 * Marks a test that needs a running Docker daemon.
 *
 * Tagged `integration` so `./gradlew test -PskipIntegration` can exclude it, and guarded by
 * [DockerAvailableCondition] so an unreachable daemon skips the test instead of failing it
 * with an unrelated container error.
 *
 * One annotation for the whole module. There were two identical copies, one per transport.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@ExtendWith(DockerAvailableCondition::class)
annotation class RequiresDocker

class DockerAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return if (dockerAvailable) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled(
                "Docker is not available - integration test skipped, NOT verified"
            )
        }
    }

    private companion object {
        // Probing is slow, so do it once per JVM.
        val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }
                .getOrDefault(false)
        }
    }
}
