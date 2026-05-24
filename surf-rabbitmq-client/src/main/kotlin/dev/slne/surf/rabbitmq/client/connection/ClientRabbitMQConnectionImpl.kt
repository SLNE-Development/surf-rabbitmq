@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.expireAfterWrite
import dev.kourier.amqp.channel.AMQPChannel
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.properties
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import it.unimi.dsi.fastutil.objects.ObjectList
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import org.apache.commons.lang3.RandomStringUtils
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class ClientRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: RabbitMQConfig
) : AbstractRabbitMQConnectionImpl(
    api = api,
    config = config,
), ClientRabbitMQConnection {
    companion object {
        private val log = logger()
    }

    private val requestTimeoutSeconds = config.requestTimeoutSeconds.seconds
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

    private val responseChunkAssembler = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.RESPONSE,
        timeout = requestTimeoutSeconds * 2
    )

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
            name = queueName + "_callback_" + RandomStringUtils.secureStrong().nextAlphanumeric(8)
            durable = false
            exclusive = true
            autoDelete = true
        }.queueName

        startConsumingResponses()
    }

    private suspend fun startConsumingResponses() {
        val consume = channel.basicConsume(callbackQueueName)

        api.scope.launch {
            for (message in consume) {
                val correlationId = message.message.properties.correlationId
                val body = message.message.body
                val deliveryTag = message.message.deliveryTag

                if (correlationId == null) {
                    channel.basicAck(deliveryTag)
                    continue
                }

                val pending = pendingRequests.getIfPresent(correlationId)
                if (pending == null) {
                    responseChunkAssembler.discard(correlationId)
                    channel.basicAck(deliveryTag)
                    continue
                }

                val result = try {
                    responseChunkAssembler.accept(correlationId, body)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    responseChunkAssembler.discard(correlationId)

                    val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                    val deferred = removedPending.second

                    if (deferred != null && !deferred.isCompleted) {
                        deferred.completeExceptionally(t)
                    }

                    channel.basicAck(deliveryTag)

                    log.atWarning()
                        .withCause(t)
                        .log("Failed to assemble RabbitMQ response chunks for correlationId $correlationId")

                    continue
                }

                when (result) {
                    RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk -> {
                        val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                        channel.basicAck(deliveryTag)

                        val deferred = removedPending.second
                        if (deferred != null && !deferred.isCompleted) {
                            deferred.complete(body)
                        }
                    }

                    RabbitPacketChunkAssembler.ChunkAcceptResult.Stored -> {
                        channel.basicAck(deliveryTag)
                    }

                    is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete -> {
                        val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                        channel.basicAck(deliveryTag)

                        val deferred = removedPending.second
                        if (deferred != null && !deferred.isCompleted) {
                            deferred.complete(result.body)
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R = withContext(api.scope.coroutineContext.minusKey(Job)) {
        val responseBytes = awaitResponse(request, responseClass)
        val response =
            RabbitPacketSerializer.deserializeResponse(api, responseBytes, responseSerializerCache)

        response as R
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

        pendingRequests.put(correlationId, request to deferred)

        try {
            val requestBodies =
                if (RabbitPacketChunking.shouldChunk(requestBytes, config.isOutgoingRequestChunkingEnabled())) {
                    RabbitPacketChunking.splitRequest(requestBytes)
                } else {
                    ObjectList.of(requestBytes)
                }

            for (requestBody in requestBodies) {
                publishChannel.basicPublish {
                    this.body = requestBody
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
            pendingRequests.invalidate(correlationId)
            throw t
        }

        return try {
            withTimeout(requestTimeoutSeconds) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.invalidate(correlationId)
            throw SurfRabbitRequestTimeoutException(request, requestTimeoutSeconds)
        } catch (t: Throwable) {
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
        RabbitPacketChunking.newCorrelationId("$correlationIdPrefix-${correlationIdSequence.incrementAndGet()}")
}