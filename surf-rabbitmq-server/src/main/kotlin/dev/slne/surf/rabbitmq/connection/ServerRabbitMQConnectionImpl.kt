package dev.slne.surf.rabbitmq.connection

import com.rabbitmq.client.AMQP
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitAck
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.listener.RabbitListenerHandlerManager
import it.unimi.dsi.fastutil.objects.ObjectList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class ServerRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: RabbitMQConfig
) : AbstractRabbitMQConnectionImpl(api, config), ServerRabbitMQConnection {
    companion object {
        private val log = logger()
    }

    private val listenerHandler = RabbitListenerHandlerManager(api, this)
    private val prefetchCount = config.serverPrefetchCount
    private val persistResponses = config.persistResponses

    private val requestChunkAssembler = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.REQUEST,
        timeout = config.requestTimeoutSeconds.seconds
    )


    override suspend fun connect() {
        super.connect()
        startConsumingRequests()
    }

    override fun registerRequestHandler(instance: Any) {
        listenerHandler.registerRequestHandler(instance)
    }

    private suspend fun startConsumingRequests() {
        mainConsumer.consume(
            queue = queueName,
            autoAck = false,
            prefetchCount = prefetchCount,
            requeueOnHandlerError = false
        ) { consumerTag, message, ack ->
            val property = message.properties
            val body = message.body
            val correlationId = property.correlationId
            val replyTo = property.replyTo

            if (correlationId == null || replyTo == null) {
                ack.nack(requeue = false)
                return@consume
            }

            try {
                when (val result = requestChunkAssembler.accept(correlationId, body)) {
                    RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk -> {
                        listenerHandler.handleRequest(
                            correlationId = correlationId,
                            replyTo = replyTo,
                            body = body,
                            ack = ack,
                        )
                    }

                    RabbitPacketChunkAssembler.ChunkAcceptResult.Stored -> {
                        // Ack stored chunks immediately so a packet with more chunks than
                        // the prefetch count cannot deadlock waiting for later chunks.
                        ack.ack()
                    }

                    is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete -> {
                        listenerHandler.handleRequest(
                            correlationId = correlationId,
                            replyTo = replyTo,
                            body = result.body,
                            ack = ack,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                requestChunkAssembler.discard(correlationId)

                log.atWarning()
                    .withCause(t)
                    .log("Failed to handle RabbitMQ request chunk for correlationId $correlationId, discarding request")

                ack.nack(requeue = false)
            }
        }
    }

    suspend fun replyToRequest(
        correlationId: String,
        replyTo: String,
        ack: RabbitAck?,
        body: ByteArray
    ) {
        val responseBodies =
            if (
                RabbitPacketChunking.supportsChunkedResponses(correlationId) &&
                RabbitPacketChunking.shouldChunk(
                    body,
                    config.isOutgoingResponseChunkingEnabled()
                )
            ) {
                RabbitPacketChunking.splitResponse(body)
            } else {
                ObjectList.of(body)
            }

        for (responseBody in responseBodies) {
            client.publish(
                exchange = "",
                routingKey = replyTo,
                body = responseBody,
                properties = AMQP.BasicProperties.Builder()
                    .correlationId(correlationId)
                    .deliveryMode(if (persistResponses) 2 else 1)
                    .build()
            )
        }

        ack?.ack()
    }
}