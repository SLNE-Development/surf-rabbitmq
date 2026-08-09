package dev.slne.surf.eventbus.rabbitmq.testing

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

/**
 * One RabbitMQ broker shared by every integration test in the JVM.
 *
 * Starting a container per test class costs several seconds each. Tests isolate themselves by
 * using unique service names instead, which is both faster and closer to production, where
 * many services share one broker.
 */
object RabbitBrokerExtension {

    private val container: RabbitMQContainer by lazy {
        RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management"))
            .withReuse(false)
            .also { it.start() }
    }

    fun connectionFactory(): ConnectionFactory = ConnectionFactory().apply {
        host = container.host
        port = container.amqpPort
        username = container.adminUsername
        password = container.adminPassword
        virtualHost = "/"
    }

    fun newConnection(name: String): Connection = connectionFactory().newConnection(name)

    fun amqpUrl(): String = container.amqpUrl

    /** Management API base URL, for assertions that need queue arguments. */
    fun managementUrl(): String = container.httpUrl

    fun adminUsername(): String = container.adminUsername
    fun adminPassword(): String = container.adminPassword

    /** Unique per test to keep parallel tests from colliding on names. */
    fun uniqueServiceName(prefix: String): String =
        // nanoTime alone can collide across forked test JVMs sharing one broker;
        // the random suffix removes that.
        "$prefix-${System.nanoTime().toString(16)}-${java.util.UUID.randomUUID().toString().take(8)}"

    /**
     * Force-closes every client connection through the management API.
     *
     * Simulates a broker outage without restarting the container, which would also wipe the
     * durable state the recovery is supposed to find intact.
     */
    fun closeAllConnections() {
        val client = java.net.http.HttpClient.newHttpClient()
        val credentials = java.util.Base64.getEncoder()
            .encodeToString("${adminUsername()}:${adminPassword()}".toByteArray())

        val list = client.send(
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("${managementUrl()}/api/connections"))
                .header("Authorization", "Basic $credentials")
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        ).body()

        // Minimal extraction: the management API returns a JSON array of connection objects.
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(list)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { name ->
                runCatching {
                    client.send(
                        java.net.http.HttpRequest.newBuilder()
                            .uri(
                                java.net.URI.create(
                                    "${managementUrl()}/api/connections/" +
                                            java.net.URLEncoder.encode(name, Charsets.UTF_8)
                                )
                            )
                            .header("Authorization", "Basic $credentials")
                            .DELETE()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                    )
                }
            }
    }
}
