@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.expireAfterWrite
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class ClientRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    config: RabbitMQConfig
) : AbstractRabbitMQConnectionImpl(
    api = api,
    config = config,
), ClientRabbitMQConnection {
    private val requestTimeoutSeconds = config.requestTimeoutSeconds.seconds
    private val maxPacketChunkSizeBytes = config.maxPacketChunkSizeBytes.coerceAtLeast(1)
    private val persistRequests = config.persistRequests

    private val pendingRequests = Caffeine.newBuilder()
        .expireAfterWrite(requestTimeoutSeconds * 2)
        .evictionListener<String, Pair<RabbitRequestPacket<*>, CompletableDeferred<ByteArray>?>> { _, pair, _ ->
            val request = pair?.first
            val deferred = pair?.second

            if (deferred != null && !deferred.isCompleted) {
                deferred.completeExceptionally(
                    SurfRabbitRequestTimeoutException(
                        request,
                        requestTimeoutSeconds
                    )
                )
            }
        }
        .build<String, Pair<RabbitRequestPacket<*>, CompletableDeferred<ByteArray>?>>()

    private val pendingResponseChunks = Caffeine.newBuilder()
        .expireAfterWrite(requestTimeoutSeconds * 2)
        .build<String, ResponseChunkAssembly>()

    private val requestSerializerCache =
        KotlinSerializerCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)
    private val responseSerializerCache =
        KotlinSerializerNameCache<RabbitResponsePacket>(api.cbor.serializersModule)

    private val correlationIdSequence = AtomicLong()
    private val correlationIdPrefix = "${api.pluginName}-${System.nanoTime()}"

    private lateinit var callbackQueueName: String
    private lateinit var publishChannel: AMQPChannel

    override suspend fun connect() {
        super.connect()
        publishChannel = connection.openChannel()

        callbackQueueName = channel.queueDeclare {
            name = queueName + "_callback_" + generateRandomQueueSuffix()
            durable = false
            exclusive = true
            autoDelete = true
        }.queueName

        startConsumingResponses()
    }

    private fun generateRandomQueueSuffix(length: Int = 8): String {
        val chars = ('a'..'z') + ('0'..'9')

        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    private suspend fun startConsumingResponses() {
        val consume = channel.basicConsume(callbackQueueName)

        api.scope.launch {
            for (message in consume) {
                val correlationId = message.message.properties.correlationId
                val body = message.message.body
                val deliveryTag = message.message.deliveryTag

                // The response must always be removed from the callback queue,
                // even if the request has already timed out on the client side.
                channel.basicAck(deliveryTag)

                if (correlationId == null) {
                    continue
                }

                val chunk = try {
                    RabbitPacketChunking.decodeChunk(body)
                } catch (_: Throwable) {
                    pendingResponseChunks.invalidate(correlationId)
                    pendingRequests.invalidate(correlationId)
                    continue
                }

                val assembly = pendingResponseChunks.get(correlationId) {
                    ResponseChunkAssembly(chunk.totalChunks)
                } ?: continue

                val fullResponse = try {
                    assembly.append(
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        payload = chunk.payload
                    )
                } catch (_: Throwable) {
                    pendingResponseChunks.invalidate(correlationId)
                    pendingRequests.invalidate(correlationId)
                    continue
                }

                if (fullResponse != null) {
                    pendingResponseChunks.invalidate(correlationId)

                    val (_, deferred) = pendingRequests.asMap().remove(correlationId) ?: continue
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(fullResponse)
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R {
        val responseBytes = awaitResponse(request, responseClass)
        val response =
            RabbitPacketSerializer.deserializeResponse(api, responseBytes, responseSerializerCache)

        return response as R
    }

    private suspend fun <R : RabbitResponsePacket> awaitResponse(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): ByteArray {
        val correlationId = nextCorrelationId()
        val deferred = CompletableDeferred<ByteArray>()

        val serializer = requestSerializerCache.get(request.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(request.javaClass.name)
        responseSerializerCache.register(responseClass)
        val requestBytes = RabbitPacketSerializer.serializeRequest(api, serializer, request)
        val requestChunks = RabbitPacketChunking.chunk(requestBytes, maxPacketChunkSizeBytes)

        pendingRequests.put(correlationId, request to deferred)

        try {
            for (requestChunk in requestChunks) {
                publishChannel.basicPublish {
                    this.body = requestChunk
                    exchange = ""
                    routingKey = queueName
                    properties = properties {
                        deliveryMode = if (persistRequests) 2u else 1u
                        this.correlationId = correlationId
                        this.replyTo = callbackQueueName

                        // If the request is still in the queue and has not yet been sent to the
                        // server, it should expire after the timeout.
                        expiration = requestTimeoutSeconds.inWholeMilliseconds.toString()
                    }
                }
            }
        } catch (t: Throwable) {
            pendingResponseChunks.invalidate(correlationId)
            pendingRequests.invalidate(correlationId)
            throw t
        }

        return try {
            withTimeout(requestTimeoutSeconds) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingResponseChunks.invalidate(correlationId)
            pendingRequests.invalidate(correlationId)
            throw SurfRabbitRequestTimeoutException(request, requestTimeoutSeconds)
        } catch (t: Throwable) {
            pendingResponseChunks.invalidate(correlationId)
            pendingRequests.invalidate(correlationId)
            throw t
        }
    }

    override suspend fun disconnect() {
        if (this::publishChannel.isInitialized) {
            publishChannel.close()
        }
        super.disconnect()
    }

    private fun nextCorrelationId(): String =
        "$correlationIdPrefix-${correlationIdSequence.incrementAndGet()}"

    private class ResponseChunkAssembly(totalChunks: Int) {
        private val expectedTotalChunks = totalChunks
        private val chunks = arrayOfNulls<ByteArray>(totalChunks)
        private var receivedChunks = 0

        fun append(chunkIndex: Int, totalChunks: Int, payload: ByteArray): ByteArray? {
            if (totalChunks != expectedTotalChunks) {
                throw IllegalStateException("Chunk total mismatch")
            }
            if (chunkIndex !in chunks.indices) {
                throw IllegalStateException("Chunk index out of bounds")
            }
            if (chunks[chunkIndex] != null) {
                throw IllegalStateException("Duplicate chunk index: $chunkIndex")
            }

            chunks[chunkIndex] = payload
            receivedChunks++

            if (receivedChunks != expectedTotalChunks) {
                return null
            }

            val fullSize = chunks.sumOf { it!!.size }
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
}
