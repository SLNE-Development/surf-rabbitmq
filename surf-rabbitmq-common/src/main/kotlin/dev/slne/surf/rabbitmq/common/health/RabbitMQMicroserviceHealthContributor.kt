package dev.slne.surf.rabbitmq.common.health

import com.google.auto.service.AutoService
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthCheckResult
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthContributor
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthStatus
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient

@AutoService(MicroserviceHealthContributor::class)
class RabbitMQMicroserviceHealthContributor : MicroserviceHealthContributor {
    override val name: String = "rabbitmq"

    override suspend fun check(): List<MicroserviceHealthCheckResult> {
        return RabbitClient.healthSnapshot().flatMap { client ->
            listOf(
                connectionHealthCheck(
                    instance = "${client.connectionName}/publisher",
                    connected = client.publisherConnected,
                ),
                connectionHealthCheck(
                    instance = "${client.connectionName}/consumer",
                    connected = client.consumerConnected,
                ),
            )
        }
    }

    private fun connectionHealthCheck(
        instance: String,
        connected: Boolean,
    ): MicroserviceHealthCheckResult {
        return if (connected) {
            MicroserviceHealthCheckResult(
                component = name,
                instance = instance,
                status = MicroserviceHealthStatus.HEALTHY,
            )
        } else {
            MicroserviceHealthCheckResult(
                component = name,
                instance = instance,
                status = MicroserviceHealthStatus.UNHEALTHY,
                message = "RabbitMQ connection is not open.",
            )
        }
    }
}