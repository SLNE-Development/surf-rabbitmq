package dev.slne.surf.eventbus.rabbitmq.common.health

import com.google.auto.service.AutoService
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthCheckResult
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthContributor
import dev.slne.surf.microservice.api.microservice.health.MicroserviceHealthStatus
import dev.slne.surf.eventbus.rabbitmq.common.connection.client.RabbitClient

@AutoService(MicroserviceHealthContributor::class)
class RabbitMQMicroserviceHealthContributor : MicroserviceHealthContributor {
    override val name: String = "rabbitmq"

    override suspend fun check(): List<MicroserviceHealthCheckResult> {
        return RabbitClient.healthSnapshot().map { client ->
            if (client.connected) {
                MicroserviceHealthCheckResult(
                    component = name,
                    instance = client.connectionName,
                    status = MicroserviceHealthStatus.HEALTHY
                )
            } else {
                MicroserviceHealthCheckResult(
                    component = name,
                    instance = client.connectionName,
                    status = MicroserviceHealthStatus.UNHEALTHY,
                    message = "RabbitMQ connection is not open."
                )
            }
        }
    }
}