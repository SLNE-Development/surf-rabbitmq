@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.rabbitmq.client.AMQP
import com.sksamuel.aedile.core.expireAfterWrite
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

    override suspend fun connect() {
        super.connect()

        callbackQueueName = mainConsumer.declareQueue(
            queue = queueName + "_callback_" + RandomStringUtils.secureStrong().nextAlphanumeric(8),
            durable = false,
            exclusive = true,
            autoDelete = true
        ).queue

        startConsumingResponses()
    }

    private suspend fun startConsumingResponses() {
        mainConsumer.consume(
            queue = callbackQueueName,
            autoAck = false,
        ) { _, message, ack ->
            val correlationId = message.properties.correlationId
            val body = message.body

            if (correlationId == null) {
                ack.ack()
                return@consume
            }

            val pending = pendingRequests.getIfPresent(correlationId)
            if (pending == null) {
                responseChunkAssembler.discard(correlationId)
                ack.ack()
                return@consume
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

                ack.ack()

                log.atWarning()
                    .withCause(t)
                    .log("Failed to assemble RabbitMQ response chunks for correlationId $correlationId")

                return@consume
            }

            when (result) {
                RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk -> {
                    val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                    ack.ack()

                    val deferred = removedPending.second
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(body)
                    }
                }

                RabbitPacketChunkAssembler.ChunkAcceptResult.Stored -> {
                    ack.ack()
                }

                is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete -> {
                    val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                    ack.ack()

                    val deferred = removedPending.second
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(result.body)
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
                client.publish(
                    exchange = "",
                    routingKey = queueName,
                    body = requestBody,
                    properties = AMQP.BasicProperties.Builder()
                        .deliveryMode(if (persistRequests) 2 else 1)
                        .correlationId(correlationId)
                        .replyTo(callbackQueueName)

                        // If the request is still in the queue and has not yet been sent to the
                        // server, it should expire after the timeout.
                        .expiration(requestTimeoutSeconds.inWholeMilliseconds.toString())
                        .build()
                )
            }
        } catch (t: Throwable) {
            pendingRequests.invalidate(correlationId)
            throw t
        }

        return try {
            withTimeout(requestTimeoutSeconds) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            pendingRequests.invalidate(correlationId)
            throw SurfRabbitRequestTimeoutException(request, requestTimeoutSeconds)
        } catch (t: Throwable) {
            pendingRequests.invalidate(correlationId)
            throw t
        }
    }

    private fun nextCorrelationId(): String =
        RabbitPacketChunking.newCorrelationId("$correlationIdPrefix-${correlationIdSequence.incrementAndGet()}")
}