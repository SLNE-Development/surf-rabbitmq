package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.api.core.environment.requireIn
import dev.slne.surf.eventbus.InternalEventBusApi
import java.nio.file.Path

private const val RABBIT_PREFIX = "SURF_EVENTBUS_RABBITMQ_"
private const val REDIS_PREFIX = "SURF_EVENTBUS_REDIS_"
private const val AUDIT_SERVICE_VARIABLE = "SURF_EVENTBUS_AUDIT_SERVICE"

/**
 * Resolves the four layers, for both transports, at one place.
 *
 * `env > plugin yaml > global yaml > built-in default`, applied **per field**. Redis used to
 * merge whole files — a plugin file with one key in it discarded every value the global file
 * set — because its config class had no sentinel to distinguish "unset" from "set to the
 * default". Both sections carry sentinels now, so both behave the same way.
 *
 * Every argument is optional. A standalone process with no config files at all resolves to the
 * environment over the built-in defaults, which is a legitimate deployment rather than an error.
 */
@InternalEventBusApi
fun resolveEventBusConfig(
    global: EventBusConfig? = null,
    plugin: EventBusConfig? = null,
    environment: EnvironmentVariables = EnvironmentVariables.system,
): EventBusSettings = EventBusSettings(
    rabbitmq = resolveRabbitMQ(global?.rabbitmq, plugin?.rabbitmq, environment),
    redis = resolveRedis(global?.redis, plugin?.redis, environment),
)

/**
 * Which *files* feed the four layers — the other half of [resolveEventBusConfig], which only
 * applies layers it is handed.
 *
 * The two transports used to answer this question separately, and only one of them got it
 * right. `SurfRabbitApiBuilder` read the global file from the platform's folder and the plugin
 * file from the consumer's; the Redis half passed the platform's folder as *both*, so the
 * `eventbus-plugin.yml` it opened belonged to nobody and no consumer could override a Redis
 * field. Answering it once is what makes the README's "both transports use all four layers,
 * identically" true.
 *
 * @param pluginDataPath the consumer's own data folder, source of the plugin layer.
 * @param platformDataPath the platform plugin's folder, holding the broker-wide file. `null`
 *   standalone, where there is no second party and [pluginDataPath] holds the global file
 *   instead.
 * @param globalFileName standalone only, where a caller may name its own global file. On a
 *   platform the broker-wide file is shared, so its name is not the caller's to choose.
 */
@InternalEventBusApi
fun resolveEventBusSettings(
    pluginDataPath: Path?,
    platformDataPath: Path?,
    environment: EnvironmentVariables = EnvironmentVariables.system,
    globalFileName: String = EventBusConfigFiles.GLOBAL_FILE_NAME,
): EventBusSettings = when {
    platformDataPath != null -> resolveEventBusConfig(
        global = EventBusConfigFiles.global(platformDataPath),
        plugin = pluginDataPath?.let { EventBusConfigFiles.plugin(it) },
        environment = environment,
    )

    pluginDataPath != null -> resolveEventBusConfig(
        global = EventBusConfigFiles.global(pluginDataPath, globalFileName),
        environment = environment,
    )

    else -> resolveEventBusConfig(environment = environment)
}

private fun resolveRabbitMQ(
    global: RabbitMQSection?,
    plugin: RabbitMQSection?,
    environment: EnvironmentVariables,
) = RabbitMQSettings(
    host = environment.optional(RABBIT_PREFIX + "HOST") {
        require("expected a non-blank host") { it.isNotBlank() }
    } ?: yaml(plugin?.host?.value, global?.host?.value) ?: EventBusDefaults.RABBIT_HOST,

    port = environment.optionalInt(RABBIT_PREFIX + "PORT") { requireIn(1..65535) }
        ?: yaml(plugin?.port?.value, global?.port?.value) ?: EventBusDefaults.RABBIT_PORT,

    username = environment.optional(RABBIT_PREFIX + "USERNAME") {
        require("expected a non-blank username") { it.isNotBlank() }
    } ?: yaml(plugin?.username?.value, global?.username?.value) ?: EventBusDefaults.RABBIT_USERNAME,

    // Blank is allowed: a broker without authentication is a legitimate local setup.
    password = environment.optional(RABBIT_PREFIX + "PASSWORD", sensitive = true)
        ?: yaml(plugin?.password?.value, global?.password?.value) ?: EventBusDefaults.RABBIT_PASSWORD,

    vhost = environment.optional(RABBIT_PREFIX + "VHOST") {
        require("expected a non-blank vhost") { it.isNotBlank() }
    } ?: yaml(plugin?.vhost?.value, global?.vhost?.value) ?: EventBusDefaults.RABBIT_VHOST,

    timeout = environment.optionalInt(RABBIT_PREFIX + "TIMEOUT") {
        require("expected a positive timeout") { it > 0 }
    } ?: yaml(plugin?.timeout?.value, global?.timeout?.value) ?: EventBusDefaults.TIMEOUT_SECONDS,

    requestTimeoutSeconds = environment.optionalInt(RABBIT_PREFIX + "REQUEST_TIMEOUT_SECONDS") {
        require("expected a positive timeout") { it > 0 }
    } ?: yaml(plugin?.requestTimeoutSeconds?.value, global?.requestTimeoutSeconds?.value)
    ?: EventBusDefaults.REQUEST_TIMEOUT_SECONDS,

    publisherPoolSize = environment.optionalInt(RABBIT_PREFIX + "PUBLISHER_POOL_SIZE") {
        require("expected a positive size") { it > 0 }
    } ?: yaml(plugin?.publisherPoolSize?.value, global?.publisherPoolSize?.value)
    ?: EventBusDefaults.PUBLISHER_POOL_SIZE,

    serverPrefetchCount = environment.optionalInt(RABBIT_PREFIX + "SERVER_PREFETCH_COUNT") {
        requireIn(0..Short.MAX_VALUE.toInt())
    } ?: yaml(plugin?.serverPrefetchCount?.value, global?.serverPrefetchCount?.value)
    ?: EventBusDefaults.SERVER_PREFETCH_COUNT,

    persistRequests = environment.optionalBoolean(RABBIT_PREFIX + "PERSIST_REQUESTS")
        ?: yaml(plugin?.persistRequests?.value, global?.persistRequests?.value)
        ?: EventBusDefaults.PERSIST_REQUESTS,

    persistResponses = environment.optionalBoolean(RABBIT_PREFIX + "PERSIST_RESPONSES")
        ?: yaml(plugin?.persistResponses?.value, global?.persistResponses?.value)
        ?: EventBusDefaults.PERSIST_RESPONSES,

    outgoingRequestChunkingEnabled =
        environment.optionalBoolean(RABBIT_PREFIX + "OUTGOING_REQUEST_CHUNKING_ENABLED")
            ?: yaml(
                plugin?.outgoingRequestChunkingEnabled?.value,
                global?.outgoingRequestChunkingEnabled?.value
            )
            ?: systemBoolean(
                name = "surf.rabbitmq.outgoingRequestChunkingEnabled",
                default = EventBusDefaults.OUTGOING_REQUEST_CHUNKING_ENABLED,
            ),

    outgoingResponseChunkingEnabled =
        environment.optionalBoolean(RABBIT_PREFIX + "OUTGOING_RESPONSE_CHUNKING_ENABLED")
            ?: yaml(
                plugin?.outgoingResponseChunkingEnabled?.value,
                global?.outgoingResponseChunkingEnabled?.value
            )
            ?: systemBoolean(
                name = "surf.rabbitmq.outgoingResponseChunkingEnabled",
                default = EventBusDefaults.OUTGOING_RESPONSE_CHUNKING_ENABLED,
            ),

    auditServiceName = environment.optional(AUDIT_SERVICE_VARIABLE) {
        require("expected a non-blank service name") { it.isNotBlank() }
    } ?: yaml(plugin?.auditServiceName?.value, global?.auditServiceName?.value)
    ?: EventBusDefaults.AUDIT_SERVICE_NAME,
)

private fun resolveRedis(
    global: RedisSection?,
    plugin: RedisSection?,
    environment: EnvironmentVariables,
) = RedisSettings(
    host = environment.optional(REDIS_PREFIX + "HOST") {
        require("expected a non-blank host") { it.isNotBlank() }
    } ?: yaml(plugin?.host?.value, global?.host?.value) ?: EventBusDefaults.REDIS_HOST,

    port = environment.optionalInt(REDIS_PREFIX + "PORT") { requireIn(1..65535) }
        ?: yaml(plugin?.port?.value, global?.port?.value) ?: EventBusDefaults.REDIS_PORT,

    password = environment.optional(REDIS_PREFIX + "PASSWORD", sensitive = true)
        ?: yaml(plugin?.password?.value, global?.password?.value),

    clientName = environment.optional(REDIS_PREFIX + "CLIENT_NAME") {
        require("expected a non-blank client name") { it.isNotBlank() }
    } ?: yaml(plugin?.clientName?.value, global?.clientName?.value)
    ?: EventBusDefaults.redisClientName(),
)

/** The plugin file wins over the global one, per field, and only where it said something. */
private fun <T : Any> yaml(plugin: T?, global: T?): T? = plugin ?: global

private fun systemBoolean(name: String, default: Boolean): Boolean {
    val rawValue = System.getProperty(name) ?: return default
    return when {
        rawValue.equals("true", ignoreCase = true) -> true
        rawValue.equals("false", ignoreCase = true) -> false
        else -> throw IllegalArgumentException(
            "System property $name must be either 'true' or 'false'."
        )
    }
}
