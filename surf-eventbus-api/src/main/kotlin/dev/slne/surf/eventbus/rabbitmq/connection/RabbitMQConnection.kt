package dev.slne.surf.eventbus.rabbitmq.connection

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.connection.EventBusConnection
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget

@InternalEventBusApi
interface RabbitMQConnection : EventBusConnection {
    /**
     * Where this connection reports message loss.
     *
     * Exposed so the event and query dispatchers can report to the same place the RabbitMQ
     * paths do. Without it they fell back to logging, and three of the seven `AuditKind`
     * values never reached the audit service at all.
     */
    val auditSink: AuditSink

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget,
    ): R

    suspend fun send(
        packet: RabbitRequestPacket<*>,
        target: RabbitTarget,
    )

    @InternalEventBusApi
    companion object {
        fun create(api: SurfRabbitApi): RabbitMQConnection = RabbitMQConnectionFactory.createConnection(api)
    }
}
