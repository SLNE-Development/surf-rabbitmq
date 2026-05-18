package dev.slne.surf.rabbitmq.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.properties
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.listener.RabbitListenerHandlerManager
import it.unimi.dsi.fastutil.objects.ObjectList
import kotlinx.coroutines.launch
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
    private val prefetchCount = config.serverPrefetchCount.toUShort()
    private val persistResponses = config.persistResponses

    private val requestChunkAssembler = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.REQUEST,
        timeout = config.requestTimeoutSeconds.seconds
    )

    private lateinit var replyChannel: AMQPChannel

    override suspend fun connect() {
        super.connect()
        replyChannel = connection.openChannel()

        channel.basicQos(count = prefetchCount)
        startConsumingRequests()
    }

    override fun registerRequestHandler(instance: Any) {
        listenerHandler.registerRequestHandler(instance)
    }

    private suspend fun startConsumingRequests() {
        val consume = channel.basicConsume(queueName)

        api.scope.launch {
            for (message in consume) {
                val property = message.message.properties
                val body = message.message.body
                val deliveryTag = message.message.deliveryTag
                val correlationId = property.correlationId
                val replyTo = property.replyTo

                if (correlationId == null || replyTo == null) {
                    nackRequest(deliveryTag)
                    continue
                }

                try {
                    when (val result = requestChunkAssembler.accept(correlationId, body)) {
                        RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk -> {
                            api.scope.launch {
                                listenerHandler.handleRequest(
                                    correlationId = correlationId,
                                    replyTo = replyTo,
                                    body = body,
                                    deliveryTag = deliveryTag
                                )
                            }
                        }

                        RabbitPacketChunkAssembler.ChunkAcceptResult.Stored -> {
                            // Ack stored chunks immediately so a packet with more chunks than
                            // the prefetch count cannot deadlock waiting for later chunks.
                            channel.basicAck(deliveryTag)
                        }

                        is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete -> {
                            api.scope.launch {
                                listenerHandler.handleRequest(
                                    correlationId = correlationId,
                                    replyTo = replyTo,
                                    body = result.body,
                                    deliveryTag = deliveryTag
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    requestChunkAssembler.discard(correlationId)

                    log.atWarning()
                        .withCause(t)
                        .log("Failed to handle RabbitMQ request chunk for correlationId $correlationId, discarding request")

                    nackRequest(deliveryTag)
                }
            }
        }
    }

    suspend fun replyToRequest(
        correlationId: String,
        replyTo: String,
        deliveryTag: ULong?,
        body: ByteArray
    ) {
        val responseBodies =
            if (
                RabbitPacketChunking.supportsChunkedResponses(correlationId) &&
                RabbitPacketChunking.shouldChunk(body, config)
            ) {
                RabbitPacketChunking.splitResponse(body)
            } else {
                ObjectList.of(body)
            }

        for (responseBody in responseBodies) {
            replyChannel.basicPublish {
                this.body = responseBody
                exchange = ""
                routingKey = replyTo
                properties = properties {
                    this.correlationId = correlationId
                    deliveryMode = if (persistResponses) 2u else 1u
                }
            }
        }

        if (deliveryTag != null) {
            channel.basicAck(deliveryTag)
        }
    }

    suspend fun nackRequest(deliveryTag: ULong?) {
        if (deliveryTag != null) {
            channel.basicNack(deliveryTag, requeue = false)
        }
    }

    override suspend fun disconnect() {
        if (this::replyChannel.isInitialized) {
            replyChannel.close()
        }
        super.disconnect()
    }
}