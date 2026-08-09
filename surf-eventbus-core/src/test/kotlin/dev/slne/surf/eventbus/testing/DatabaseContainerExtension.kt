package dev.slne.surf.eventbus.testing

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One PostgreSQL container for the audit suite.
 *
 * **Nothing uses this yet, on purpose.** The audit suite (spec tests 38–49) needs the writing
 * microservice, and that is blocked on a shading defect in surf-database-r2dbc — see
 * `docs/superpowers/notes/2026-08-01-audit-microservice-blocked.md`. Cases 38, 47 and 48 in
 * particular cannot be written without something that reads the reports back out of a table.
 *
 * The extension exists now so that unblocking the microservice is the only remaining step,
 * rather than unblocking it *and* rediscovering how the suite reaches a database.
 */
object DatabaseContainerExtension {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("surf_eventbus_audit")
            .withUsername("surf")
            .withPassword("surf")
            .also { it.start() }
    }

    fun jdbcUrl(): String = container.jdbcUrl
    fun username(): String = container.username
    fun password(): String = container.password

    /** `host:port/database`, for an r2dbc connection string. */
    fun hostPortDatabase(): String =
        "${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
