package dev.slne.surf.rabbitmq.connection

import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.listener.RabbitListenerHandlerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class ServerRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    config: RabbitMQConfig
) : AbstractRabbitMQConnectionImpl(api, config), ServerRabbitMQConnection {
    private val listenerHandler = RabbitListenerHandlerManager(api, this)
    private val prefetchCount = config.serverPrefetchCount.coerceAtLeast(0).toUShort()
    private val persistResponses = config.persistResponses
    private val maxPacketChunkSizeBytes = config.maxPacketChunkSizeBytes.coerceAtLeast(1)
    private val requestTimeoutSeconds = config.requestTimeoutSeconds.seconds

    private lateinit var replyChannel: AMQPChannel
    private val pendingRequestChunks = ConcurrentHashMap<String, RequestChunkAssembly>()

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

                val chunk = try {
                    RabbitPacketChunking.decodeChunk(body)
                } catch (_: Throwable) {
                    nackRequest(deliveryTag)
                    continue
                }

                val assembledRequest = assembleRequestChunk(
                    correlationId = correlationId,
                    replyTo = replyTo,
                    deliveryTag = deliveryTag,
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = chunk.totalChunks,
                    payload = chunk.payload
                ) ?: continue

                api.scope.launch {
                    listenerHandler.handleRequest(
                        correlationId = correlationId,
                        replyTo = replyTo,
                        body = assembledRequest.body,
                        deliveryTag = assembledRequest.deliveryTag
                    )
                }
            }
        }
    }

    suspend fun replyToRequest(
        correlationId: String,
        replyTo: String,
        deliveryTag: ULong,
        body: ByteArray
    ) {
        val responseChunks = RabbitPacketChunking.chunk(body, maxPacketChunkSizeBytes)

        for (responseChunk in responseChunks) {
            replyChannel.basicPublish {
                this.body = responseChunk
                exchange = ""
                routingKey = replyTo
                properties = properties {
                    this.correlationId = correlationId
                    deliveryMode = if (persistResponses) 2u else 1u
                }
            }
        }

        channel.basicAck(deliveryTag)
    }

    suspend fun nackRequest(deliveryTag: ULong) {
        channel.basicNack(deliveryTag, requeue = false)
    }

    override suspend fun disconnect() {
        if (this::replyChannel.isInitialized) {
            replyChannel.close()
        }
        super.disconnect()
    }

    private suspend fun assembleRequestChunk(
        correlationId: String,
        replyTo: String,
        deliveryTag: ULong,
        chunkIndex: Int,
        totalChunks: Int,
        payload: ByteArray
    ): AssembledRequest? {
        if (totalChunks <= 1) {
            return AssembledRequest(payload, deliveryTag)
        }

        val assembly = pendingRequestChunks.getOrPut(correlationId) {
            val timeoutJob = api.scope.launch {
                delay(requestTimeoutSeconds)
                val stale = pendingRequestChunks[correlationId] ?: return@launch
                if (pendingRequestChunks.remove(correlationId, stale)) {
                    nackRequest(stale.deliveryTag)
                }
            }

            RequestChunkAssembly(
                replyTo = replyTo,
                totalChunks = totalChunks,
                deliveryTag = deliveryTag,
                timeoutJob = timeoutJob
            )
        }

        if (assembly.replyTo != replyTo || assembly.totalChunks != totalChunks) {
            assembly.timeoutJob.cancel()
            pendingRequestChunks.remove(correlationId)
            nackRequest(assembly.deliveryTag)
            nackRequest(deliveryTag)
            return null
        }

        val shouldAcknowledgeCurrentChunk = assembly.deliveryTag != deliveryTag

        val fullRequest = try {
            assembly.append(chunkIndex, payload)
        } catch (_: Throwable) {
            assembly.timeoutJob.cancel()
            pendingRequestChunks.remove(correlationId)
            if (shouldAcknowledgeCurrentChunk) {
                nackRequest(deliveryTag)
            }
            nackRequest(assembly.deliveryTag)
            return null
        }

        if (shouldAcknowledgeCurrentChunk) {
            channel.basicAck(deliveryTag)
        }

        if (fullRequest != null) {
            assembly.timeoutJob.cancel()
            pendingRequestChunks.remove(correlationId)
            return AssembledRequest(fullRequest, assembly.deliveryTag)
        }

        return null
    }

    private class RequestChunkAssembly(
        val replyTo: String,
        val totalChunks: Int,
        val deliveryTag: ULong,
        val timeoutJob: Job
    ) {
        private val chunks = arrayOfNulls<ByteArray>(totalChunks)
        private var receivedChunks = 0

        fun append(chunkIndex: Int, payload: ByteArray): ByteArray? {
            if (chunkIndex !in chunks.indices) {
                throw IllegalArgumentException(
                    "Chunk index out of bounds: index=$chunkIndex, expected index in 0..${chunks.lastIndex}"
                )
            }
            if (chunks[chunkIndex] != null) {
                throw IllegalStateException("Duplicate chunk index: $chunkIndex")
            }

            chunks[chunkIndex] = payload
            receivedChunks++

            if (receivedChunks != totalChunks) {
                return null
            }

            val fullSize = chunks.sumOf {
                it?.size ?: throw IllegalStateException("Missing chunk")
            }
            val fullPayload = ByteArray(fullSize)
            var offset = 0
            for (chunk in chunks) {
                val value = chunk ?: throw IllegalStateException("Missing chunk")
                value.copyInto(fullPayload, destinationOffset = offset)
                offset += value.size
            }
            return fullPayload
        }
    }

    private data class AssembledRequest(
        val body: ByteArray,
        val deliveryTag: ULong,
    )
}
