@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.eventbus.rabbitmq.core.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ShutdownSignalException
import com.sksamuel.aedile.core.expireAfterWrite
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditService
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.rabbitmq.audit.RabbitAuditSink
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitRequestException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitConnectionException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitConnectionLostException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.api.version.RabbitMqVersion
import dev.slne.surf.eventbus.rabbitmq.common.connection.RabbitConnectionListener
import dev.slne.surf.eventbus.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.eventbus.rabbitmq.common.connection.consumer.RabbitAck
import dev.slne.surf.eventbus.rabbitmq.common.connection.consumer.RabbitConsumer
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.eventbus.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.eventbus.rabbitmq.common.topology.RabbitTopologyDeclarer
import dev.slne.surf.eventbus.rabbitmq.core.publish.MessageKind
import dev.slne.surf.eventbus.rabbitmq.core.retry.RetryPublisher
import dev.slne.surf.eventbus.rabbitmq.core.rpc.BreakerGuardedRpc
import dev.slne.surf.eventbus.common.circuitbreaker.CircuitBreakerRegistry
import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.rabbitmq.shared.serialization.KotlinSerializerNameCache
import dev.slne.surf.eventbus.rabbitmq.listener.RabbitListenerHandlerManager
import it.unimi.dsi.fastutil.objects.ObjectList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Merges the former `ClientRabbitMQConnectionImpl` and `ServerRabbitMQConnectionImpl`.
 *
 * Every process publishes requests to `surf.rpc` addressed by [RabbitTarget.routingKey] and
 * consumes its own reply queue. A process additionally consumes its service and instance
 * queues only if it registered a handler or an RPC service ([RabbitListenerHandlerManager.hasHandlers]).
 */
class RabbitConnectionImpl(private val api: SurfRabbitApi) : RabbitMQConnection {

    companion object {
        private val log = logger()
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }

    private val client = RabbitClient.create(api.config, api.identity.instanceId)

    private val auditServiceName = api.config.getAuditServiceName()

    // Lazy: creating the generated proxy touches api.connection, which is this very instance
    // while it is still being constructed. Deferred behind an AuditSink wrapper so no
    // constructor-time code forces it - the first report (if any) resolves it lazily instead.
    private val auditSinkDelegate: RabbitAuditSink by lazy {
        RabbitAuditSink(
            proxy = api.rpc<AuditService>(target = RabbitTarget.ServiceTarget(auditServiceName)),
            serviceName = api.identity.serviceName,
            auditServiceName = auditServiceName,
        )
    }
    val auditSink: AuditSink = object : AuditSink {
        override suspend fun report(report: AuditReport) = auditSinkDelegate.report(report)
    }

    val retryPublisher = RetryPublisher(client, auditSink, api.identity.instanceId)

    /**
     * The registry's predicate is the same closed transport list [BreakerGuardedRpc] retries
     * on, so the breaker and the retry agree on what counts as a transport failure.
     */
    private val breakerRegistry = CircuitBreakerRegistry(
        failureThreshold = 5,
        openDuration = 30.seconds,
        isFailure = {
            it is SurfRabbitServiceUnavailableException || it is SurfRabbitConnectionException
        }
    )

    private val guardedRpc = BreakerGuardedRpc(breakerRegistry)

    /**
     * Fails a pending RPC request the moment the broker returns it as unroutable, instead of
     * waiting out the full request timeout. `messageId == correlationId` for the RPC path;
     * `send()` (fire-and-forget) mints its own.
     */
    private val returnListener = ReturnListenerBridge(
        api.scope,
        api.identity.serviceName,
        api.identity.instanceId,
        auditServiceName,
        auditSink
    ) { messageId, routingKey, reason ->
        val pending = pendingRequests.asMap().remove(messageId) ?: return@ReturnListenerBridge
        pending.second?.completeExceptionally(
            SurfRabbitServiceUnavailableException(routingKey, reason)
        )
    }
    private val listenerHandler = RabbitListenerHandlerManager(api, this)

    private val requestTimeoutSeconds = api.config.getRequestTimeoutSeconds().seconds
    private val persistRequests = api.config.isPersistRequests()
    private val persistResponses = api.config.isPersistResponses()
    private val prefetchCount = api.config.getServerPrefetchCount()

    private lateinit var replyConsumer: RabbitConsumer
    private lateinit var replyQueueName: String

    private var serviceConsumer: RabbitConsumer? = null
    private var instanceConsumer: RabbitConsumer? = null

    private class ReceivedResponse(val body: ByteArray, val senderVersion: RabbitMqVersion)

    private data class ReplyEndpoint(
        val queueName: String,
        val connectionGeneration: Long
    )

    private val replyEndpoint = MutableStateFlow<ReplyEndpoint?>(null)

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
        timeout = requestTimeoutSeconds * 2,
        scope = api.scope,
        auditSink = auditSink,
        serviceName = api.identity.serviceName,
        instanceId = api.identity.instanceId
    )

    private val requestChunkAssembler = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.REQUEST,
        timeout = requestTimeoutSeconds,
        scope = api.scope,
        auditSink = auditSink,
        serviceName = api.identity.serviceName,
        instanceId = api.identity.instanceId
    )

    private val requestSerializerCache =
        KotlinSerializerCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)
    private val responseSerializerCache =
        KotlinSerializerNameCache<RabbitResponsePacket>(api.cbor.serializersModule)

    private val correlationIdSequence = AtomicLong()
    private val correlationIdPrefix = "${api.identity.instanceId}-${System.nanoTime()}"

    private val connectionListener = object : RabbitConnectionListener {
        override fun onConnectionLost(cause: ShutdownSignalException) {
            markReplyConsumerUnavailable(SurfRabbitConnectionLostException(api.identity.instanceId, cause))
        }

        override fun onRecoveryStarted() {
            replyEndpoint.value = null
        }

        override fun onRecoveryCompleted(generation: Long) {
            // The reply queue name is stable per instance. Topology recovery has already
            // re-declared it and re-attached the consumer; the endpoint only needs the
            // new connection generation.
            replyEndpoint.value = ReplyEndpoint(
                queueName = replyQueueName,
                connectionGeneration = generation
            )
        }
    }

    init {
        client.addConnectionListener(connectionListener)
        // Before connect(): publisher channels are created lazily on first publish, so a
        // listener installed here reaches every channel that will ever exist, including
        // ones created after a reconnect.
        client.setReturnListener(returnListener)
    }

    override suspend fun connect() {
        val declareConsumer = client.newConsumer("declare")

        // Every process declares the exchange and the retry tiers; both are idempotent.
        declareConsumer.withChannel { channel ->
            val declarer = RabbitTopologyDeclarer(channel)
            declarer.declareExchanges()
            declarer.declareRetryTiers(api.config.getRetryTtlMillis())
        }

        // Reply and instance queues get their own consumer, and therefore their own channel.
        // Sharing one channel meant a failed declare took the RPC reply path down with it.
        replyConsumer = client.newConsumer("reply")
        replyQueueName = replyConsumer.withChannel { channel ->
            RabbitTopologyDeclarer(channel).declareReplyQueue(api.identity.instanceId)
        }
        startConsumingResponses(replyQueueName)

        // Only a process that actually handles requests declares and consumes request
        // queues. It consumes TWO of them: the shared service queue (competing consumers)
        // and its own instance queue (InstanceTarget). Without the instance queue, every
        // InstanceTarget send would be unroutable.
        if (listenerHandler.hasHandlers()) {
            val serviceConsumer = client.newConsumer("service")
            val serviceQueue = serviceConsumer.withChannel { channel ->
                RabbitTopologyDeclarer(channel).declareServiceQueue(api.identity.serviceName)
            }
            startConsumingRequests(serviceConsumer, serviceQueue)
            this.serviceConsumer = serviceConsumer

            val instanceConsumer = client.newConsumer("instance")
            val instanceQueue = instanceConsumer.withChannel { channel ->
                RabbitTopologyDeclarer(channel).declareInstanceQueue(api.identity.instanceId)
            }
            startConsumingRequests(instanceConsumer, instanceQueue)
            this.instanceConsumer = instanceConsumer
        }

        replyEndpoint.value = ReplyEndpoint(
            queueName = replyQueueName,
            connectionGeneration = client.connectionGeneration
        )
    }

    override suspend fun disconnect() {
        client.close()
    }

    override suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget) {
        val serializer = requestSerializerCache.get(packet.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(packet.javaClass.name)

        val body = RabbitPacketSerializer.serializeRequest(api, serializer, packet)
        val correlationId = nextCorrelationId()

        val bodies =
            if (RabbitPacketChunking.shouldChunk(body, api.config.isOutgoingRequestChunkingEnabled())) {
                RabbitPacketChunking.splitRequest(body)
            } else {
                ObjectList.of(body)
            }

        // Fire-and-forget has no pending deferred to fail, so it needs its own messageId
        // rather than reusing correlationId. The confirm ordering (basic.return arrives
        // before the confirm ack of the same message) makes a single post-publish check
        // race-free: by the time publish() returns, any return has already been recorded.
        val messageId = java.util.UUID.randomUUID().toString()
        returnListener.register(messageId)

        try {
            for (chunkBody in bodies) {
                client.publish(
                    exchange = RabbitTopology.RPC_EXCHANGE,
                    routingKey = target.routingKey,
                    body = chunkBody,
                    // No replyTo: the receiver must not attempt to answer. correlationId is
                    // still required by the request chunk assembler, even for a single chunk.
                    properties = properties(
                        MessageKind.FIRE_AND_FORGET,
                        correlationId = correlationId,
                        messageId = messageId
                    ),
                    mandatory = true
                )
            }

            returnListener.returnedReason(messageId)?.let { reason ->
                throw SurfRabbitServiceUnavailableException(target.routingKey, reason)
            }
        } finally {
            returnListener.unregister(messageId)
        }
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

    private suspend fun startConsumingResponses(queue: String) {
        replyConsumer.consume(
            queue = queue,
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

    private suspend fun startConsumingRequests(consumer: RabbitConsumer, queue: String) {
        consumer.consume(
            queue = queue,
            autoAck = false,
            prefetchCount = prefetchCount,
            requeueOnHandlerError = false
        ) { _, message, ack ->
            val property = message.properties
            val body = message.body
            val correlationId = property.correlationId
            val replyTo = property.replyTo
            val senderVersion = RabbitMqVersion.fromHeaders(property.headers)

            if (correlationId == null) {
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
                            properties = property,
                            originQueue = queue,
                            senderVersion = senderVersion,
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
                            properties = property,
                            originQueue = queue,
                            senderVersion = senderVersion,
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
                    api.config.isOutgoingResponseChunkingEnabled()
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
                properties = properties(MessageKind.RPC_RESPONSE, correlationId = correlationId)
            )
        }

        ack?.ack()
    }

    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R = withContext(api.scope.coroutineContext.minusKey(Job)) {
        guardedRpc.call(target) {
            val received = withTimeoutOrNull(requestTimeoutSeconds) {
                awaitResponse(
                    request = request,
                    responseClass = responseClass,
                    target = target
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
    }

    private suspend fun <R : RabbitResponsePacket> awaitResponse(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
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
        // messageId == correlationId on the RPC path: a broker return fails this exact
        // pending deferred the moment it arrives, instead of waiting out the request timeout.
        returnListener.register(correlationId)

        try {
            if (replyEndpoint.value != endpoint) {
                throw SurfRabbitConnectionLostException(api.identity.instanceId)
            }

            val requestBodies =
                if (RabbitPacketChunking.shouldChunk(
                        requestBytes,
                        api.config.isOutgoingRequestChunkingEnabled()
                    )
                ) {
                    RabbitPacketChunking.splitRequest(requestBytes)
                } else {
                    ObjectList.of(requestBytes)
                }

            for (requestBody in requestBodies) {
                client.publish(
                    exchange = RabbitTopology.RPC_EXCHANGE,
                    routingKey = target.routingKey,
                    body = requestBody,
                    mandatory = true,
                    properties = properties(
                        MessageKind.RPC_REQUEST,
                        correlationId = correlationId,
                        replyTo = endpoint.queueName,
                        messageId = correlationId
                    ),
                    expectedConnectionGeneration = endpoint.connectionGeneration
                )
            }

            return deferred.await()
        } finally {
            pendingRequests.asMap().remove(correlationId, pending)
            responseChunkAssembler.discard(correlationId)
            returnListener.unregister(correlationId)
        }
    }

    private fun nextCorrelationId(): String =
        RabbitPacketChunking.newCorrelationId("$correlationIdPrefix-${correlationIdSequence.incrementAndGet()}")

    private fun properties(
        kind: MessageKind,
        correlationId: String? = null,
        replyTo: String? = null,
        messageId: String? = null
    ): AMQP.BasicProperties = AMQP.BasicProperties.Builder()
        .deliveryMode(kind.deliveryMode(persistRequests, persistResponses))
        .also { builder ->
            correlationId?.let(builder::correlationId)
            replyTo?.let(builder::replyTo)
            messageId?.let(builder::messageId)
            kind.expirationMillis(requestTimeoutSeconds)?.let(builder::expiration)
        }
        .headers(mapOf(RabbitMqVersion.AMQP_HEADER to RabbitMqVersion.CURRENT.toString()))
        .build()
}
