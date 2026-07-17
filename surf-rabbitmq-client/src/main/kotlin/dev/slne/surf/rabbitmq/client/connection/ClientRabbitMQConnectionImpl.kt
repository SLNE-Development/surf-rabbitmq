@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.rabbitmq.client.AMQP
import com.sksamuel.aedile.core.expireAfterWrite
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.*
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.version.RabbitMqVersion
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import org.apache.commons.lang3.RandomStringUtils
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class ClientRabbitMQConnectionImpl(
    private val api: RabbitMQApi,
    private val config: CommonRabbitMQConfig
) : AbstractRabbitMQConnectionImpl(
    api = api,
    config = config,
), ClientRabbitMQConnection {
    companion object {
        private val log = logger()
        private const val MAX_PENDING_REQUESTS = 50_000L
        private val VERSION_HEADERS = mapOf(
            RabbitMqVersion.AMQP_HEADER to RabbitMqVersion.CURRENT.toString()
        )
    }

    private val requestTimeoutSeconds = config.getRequestTimeoutSeconds().seconds
    private val persistRequests = config.isPersistRequests()

    private class ReceivedResponse(val body: ByteArray, val senderVersion: RabbitMqVersion)

    private val pendingRequests = Caffeine.newBuilder()
        .maximumSize(MAX_PENDING_REQUESTS)
        .expireAfterWrite(requestTimeoutSeconds * 2)
        .evictionListener<String, Pair<RabbitRequestPacket<*>, CompletableDeferred<ReceivedResponse>?>> { _, pair, cause ->
            val request = pair?.first
            val deferred = pair?.second

            if (deferred != null && !deferred.isCompleted) {
                val failure = if (cause == RemovalCause.SIZE) {
                    SurfRabbitRequestException(
                        "Pending RabbitMQ request capacity ($MAX_PENDING_REQUESTS) was exceeded"
                    )
                } else {
                    SurfRabbitRequestTimeoutException(
                        request,
                        requestTimeoutSeconds
                    )
                }
                deferred.completeExceptionally(failure)
            }
        }
        .build<String, Pair<RabbitRequestPacket<*>, CompletableDeferred<ReceivedResponse>?>>()

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
    private val pendingLifecycleMutex = Mutex()
    private var disconnecting = false

    private lateinit var callbackQueueName: String


    override suspend fun onConnected() {
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
            val senderVersion = RabbitMqVersion.fromHeaders(message.properties.headers)

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
                responseChunkAssembler.accept(correlationId, body, senderVersion)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                t.rethrowIfFatal()

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
                    val deferred = removedPending.second
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(ReceivedResponse(body, senderVersion))
                    }
                    ack.ack()
                }

                RabbitPacketChunkAssembler.ChunkAcceptResult.Stored -> {
                    ack.ack()
                }

                is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete -> {
                    val removedPending = pendingRequests.asMap().remove(correlationId) ?: pending
                    val deferred = removedPending.second
                    if (deferred != null && !deferred.isCompleted) {
                        deferred.complete(ReceivedResponse(result.body, senderVersion))
                    }
                    ack.ack()
                }
            }
        }
    }

    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R = withContext(api.scope.coroutineContext.minusKey(Job)) {
        val received = awaitResponse(request, responseClass)
        val response =
            RabbitPacketSerializer.deserializeResponse(api, received.body, responseSerializerCache)
        response.senderVersion = received.senderVersion

        if (!responseClass.isInstance(response)) {
            throw SurfRabbitEnvelopeDeserializationException(
                ClassCastException(
                    "Expected response ${responseClass.name}, but received ${response.javaClass.name}"
                )
            )
        }

        responseClass.cast(response)
    }

    private suspend fun <R : RabbitResponsePacket> awaitResponse(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): ReceivedResponse {
        val correlationId = nextCorrelationId()
        val deferred = CompletableDeferred<ReceivedResponse>()

        val serializer = requestSerializerCache.get(request.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(request.javaClass.name)
        responseSerializerCache.register(responseClass)
        val requestBytes = RabbitPacketSerializer.serializeRequest(api, serializer, request)

        pendingLifecycleMutex.withLock {
            if (disconnecting) throw SurfRabbitConnectionClosedException("send request")
            pendingRequests.put(correlationId, request to deferred)
        }
        if (deferred.isCompleted) return deferred.await()

        try {
            val requestChunks =
                if (RabbitPacketChunking.shouldChunk(requestBytes, config.isOutgoingRequestChunkingEnabled())) {
                    RabbitPacketChunking.splitRequest(requestBytes)
                } else {
                    null
                }

            val requestProperties = AMQP.BasicProperties.Builder()
                .deliveryMode(if (persistRequests) 2 else 1)
                .correlationId(correlationId)
                .replyTo(callbackQueueName)
                .headers(VERSION_HEADERS)

                // If the request is still in the queue and has not yet been sent to the
                // server, it should expire after the timeout.
                .expiration(requestTimeoutSeconds.inWholeMilliseconds.toString())
                .build()

            if (requestChunks == null) {
                client.publish(
                    exchange = "",
                    routingKey = queueName,
                    body = requestBytes,
                    properties = requestProperties
                )
            } else {
                for (requestBody in requestChunks) {
                    client.publish(
                        exchange = "",
                        routingKey = queueName,
                        body = requestBody,
                        properties = requestProperties
                    )
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

    override suspend fun disconnect() {
        val failure = SurfRabbitConnectionClosedException("await response")
        val requestsToFail = pendingLifecycleMutex.withLock {
            disconnecting = true
            pendingRequests.asMap().values.toList()
        }
        requestsToFail.forEach { (_, deferred) ->
            deferred?.completeExceptionally(failure)
        }

        try {
            super.disconnect()
        } finally {
            responseChunkAssembler.clear()
            pendingRequests.invalidateAll()
            pendingRequests.cleanUp()
        }
    }
}
