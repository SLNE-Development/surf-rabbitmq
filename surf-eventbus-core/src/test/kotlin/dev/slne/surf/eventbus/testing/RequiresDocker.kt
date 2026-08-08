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

/**
 * Skips when Docker is unreachable — unless `-PrequireIntegration` is set, in which case it
 * fails instead.
 *
 * A skip is the right default on a developer machine without a daemon. It is the wrong answer
 * on CI, where "integration test skipped, NOT verified" is indistinguishable from a green run
 * and silently hides every defect these tests exist to catch. CI passes `-PrequireIntegration`
 * so an absent daemon is a build failure rather than a quiet no-op.
 */
class DockerAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        if (dockerAvailable) {
            return ConditionEvaluationResult.enabled("Docker is available")
        }

        check(!integrationRequired) {
            "Docker is not available, but -PrequireIntegration was set. Integration tests are " +
                    "mandatory in this build; a skip here would report success without " +
                    "verifying anything."
        }

        return ConditionEvaluationResult.disabled(
            "Docker is not available - integration test skipped, NOT verified"
        )
    }

    private companion object {
        // Probing is slow, so do it once per JVM.
        val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }
                .getOrDefault(false)
        }

        val integrationRequired: Boolean =
            System.getProperty("surf.eventbus.requireIntegration").toBoolean()
    }
}
