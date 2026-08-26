@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ShutdownSignalException
import com.sksamuel.aedile.core.expireAfterWrite
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.version.RabbitMqVersion
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionListener
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import it.unimi.dsi.fastutil.objects.ObjectList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.Serial
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    }

    private val requestTimeoutSeconds = config.getRequestTimeoutSeconds().seconds
    private val persistRequests = config.isPersistRequests()

    private class ReceivedResponse(val body: ByteArray, val senderVersion: RabbitMqVersion)

    private val pendingRequests = Caffeine.newBuilder()
        .expireAfterWrite(requestTimeoutSeconds * 2)
        .evictionListener<String, Pair<RabbitRequestPacket<*>, CompletableDeferred<ReceivedResponse>?>> { _, pair, _ ->
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

    private val callbackQueueName = AtomicReference<String?>(null)
    private val recoveredCallbackQueueName = AtomicReference<String?>(null)
    private val replyEndpoint = MutableStateFlow<ReplyEndpoint?>(null)

    private val connectionListener = object : RabbitConnectionListener {
        override fun onConnectionLost(cause: ShutdownSignalException) {
            markReplyConsumerUnavailable(SurfRabbitConnectionLostException(api.pluginName, cause))
        }

        override fun onRecoveryStarted() {
            replyEndpoint.value = null
            recoveredCallbackQueueName.set(null)
        }

        override fun onQueueRecovered(oldName: String, newName: String) {
            if (callbackQueueName.compareAndSet(oldName, newName)) {
                recoveredCallbackQueueName.set(newName)
            }
        }

        override fun onRecoveryCompleted(generation: Long) {
            val queueName = recoveredCallbackQueueName.get() ?: return

            replyEndpoint.value = ReplyEndpoint(
                queueName = queueName,
                consumerGeneration = generation
            )
        }
    }

    init {
        client.addConsumerConnectionListener(connectionListener)
    }

    override suspend fun connect() {
        super.connect()

        val callbackQueueName = mainConsumer.declareQueue(
            queue = client.newCallbackQueueName(),
            durable = false,
            exclusive = true,
            autoDelete = true
        ).queue

        this.callbackQueueName.set(callbackQueueName)
        recoveredCallbackQueueName.set(null)

        startConsumingResponses(callbackQueueName)

        replyEndpoint.value = ReplyEndpoint(
            queueName = callbackQueueName,
            consumerGeneration = client.consumerConnectionGeneration
        )
    }

    private fun markReplyConsumerUnavailable(cause: Throwable) {
        replyEndpoint.value = null

        val pendingMap = pendingRequests.asMap()

        for ((correlationId, pending) in pendingMap) {
            if (!pendingMap.remove(correlationId, pending)) {
                continue
            }

            responseChunkAssembler.discard(correlationId)

            val deferred = pending.second
            if (deferred != null && !deferred.isCompleted) {
                deferred.completeExceptionally(cause)
            }
        }
    }

    private suspend fun startConsumingResponses(callbackQueueName: String) {
        mainConsumer.consume(
            queue = callbackQueueName,
            autoAck = false,
            onCancelled = { consumerTag ->
                markReplyConsumerUnavailable(SurfRabbitRequestException("RabbitMQ reply consumer '$consumerTag' was cancelled"))
            }
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
                        deferred.complete(ReceivedResponse(body, senderVersion))
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
                        deferred.complete(ReceivedResponse(result.body, senderVersion))
                    }
                }
            }
        }
    }

    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R = withContext(api.scope.coroutineContext.minusKey(Job)) {
        val received = withTimeoutOrNull(requestTimeoutSeconds) {
            awaitResponse(
                request = request,
                responseClass = responseClass
            )
        } ?: throw SurfRabbitRequestTimeoutException(
            request,
            requestTimeoutSeconds
        )

        val response = RabbitPacketSerializer.deserializeResponse(
            api,
            received.body,
            responseSerializerCache
        )

        response.senderVersion = received.senderVersion

        @Suppress("UNCHECKED_CAST")
        response as R
    }

    private suspend fun <R : RabbitResponsePacket> awaitResponse(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): ReceivedResponse {
        val endpoint = replyEndpoint
            .filterNotNull()
            .first()

        val correlationId = nextCorrelationId()
        val deferred = CompletableDeferred<ReceivedResponse>()

        val serializer = requestSerializerCache.get(request.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(request.javaClass.name)

        responseSerializerCache.register(responseClass)

        val requestBytes = RabbitPacketSerializer.serializeRequest(api, serializer, request)

        val pending = request to deferred
        pendingRequests.put(correlationId, pending)

        try {
            if (replyEndpoint.value != endpoint) {
                throw SurfRabbitConnectionLostException(api.pluginName)
            }

            val requestBodies =
                if (RabbitPacketChunking.shouldChunk(
                        requestBytes,
                        config.isOutgoingRequestChunkingEnabled()
                    )
                ) {
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
                        .replyTo(endpoint.queueName)
                        .headers(mapOf(RabbitMqVersion.AMQP_HEADER to RabbitMqVersion.CURRENT.toString()))

                        // If the request is still in the queue and has not yet been sent to the
                        // server, it should expire after the timeout.
                        .expiration(requestTimeoutSeconds.inWholeMilliseconds.toString())
                        .build(),
                )
            }

            return deferred.await()
        } finally {
            pendingRequests.asMap().remove(correlationId, pending)
            responseChunkAssembler.discard(correlationId)
        }
    }

    private fun nextCorrelationId(): String =
        RabbitPacketChunking.newCorrelationId("$correlationIdPrefix-${correlationIdSequence.incrementAndGet()}")

    private data class ReplyEndpoint(
        val queueName: String,
        val consumerGeneration: Long
    )

    class SurfRabbitConnectionLostException(
        connectionName: String,
        cause: Throwable? = null
    ) : SurfRabbitRequestException(
        "RabbitMQ connection '$connectionName' was lost while waiting for a response",
        cause
    ) {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 4792814117769176767L
        }
    }
}