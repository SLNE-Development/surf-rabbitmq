package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.environment.EnvironmentVariables

/**
 * Fails the start when a pre-2.0 environment variable is still set.
 *
 * Every variable moved under `SURF_EVENTBUS_*`. Silently ignoring an old name is the worst
 * available outcome: the value falls back to its default — `localhost` for both hosts — and the
 * process comes up looking healthy while pointing at nothing.
 */
object LegacyEnvironmentGuard {

    private const val RABBIT_LEGACY_PREFIX = "SURF_RABBITMQ_"
    private const val RABBIT_PREFIX = "SURF_EVENTBUS_RABBITMQ_"
    private const val REDIS_LEGACY_PREFIX = "SURF_REDIS_"
    private const val REDIS_PREFIX = "SURF_EVENTBUS_REDIS_"

    private val rabbitSuffixes = listOf(
        "HOST", "PORT", "USERNAME", "PASSWORD", "VHOST", "TIMEOUT",
        "REQUEST_TIMEOUT_SECONDS", "PUBLISHER_POOL_SIZE", "SERVER_PREFETCH_COUNT",
        "PERSIST_REQUESTS", "PERSIST_RESPONSES",
        "OUTGOING_REQUEST_CHUNKING_ENABLED", "OUTGOING_RESPONSE_CHUNKING_ENABLED"
    )

    private val redisSuffixes = listOf("HOST", "PORT", "PASSWORD", "CLIENT_NAME")

    /**
     * @throws IllegalStateException naming every legacy variable found and its replacement.
     *   Values are never included — one of them is a password.
     */
    fun check(environment: EnvironmentVariables = EnvironmentVariables.system) {
        val found = buildList {
            for (suffix in rabbitSuffixes) {
                addIfPresent(environment, RABBIT_LEGACY_PREFIX + suffix, RABBIT_PREFIX + suffix)
            }
            for (suffix in redisSuffixes) {
                addIfPresent(environment, REDIS_LEGACY_PREFIX + suffix, REDIS_PREFIX + suffix)
            }
        }

        if (found.isEmpty()) return

        error(
            found.joinToString(
                prefix = "Pre-2.0 environment variables are set but no longer read:\n",
                separator = "\n"
            )
        )
    }

    private fun MutableList<String>.addIfPresent(
        environment: EnvironmentVariables,
        legacyName: String,
        newName: String
    ) {
        // sensitive = true: a present password must not reach the message.
        if (environment.optional(legacyName, sensitive = true) != null) {
            add("  $legacyName is set but no longer read. -> rename it to $newName")
        }
    }
}
