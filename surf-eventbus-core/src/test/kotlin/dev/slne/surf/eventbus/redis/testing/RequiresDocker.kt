package dev.slne.surf.eventbus.redis.testing

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory

/**
 * Marks a test that needs a running Docker daemon.
 *
 * A copy of `surf-eventbus-rabbitmq-core`'s annotation of the same name; both move to
 * `surf-eventbus-test` in Plan 4 Task 5.
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
        val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }
                .getOrDefault(false)
        }
    }
}
