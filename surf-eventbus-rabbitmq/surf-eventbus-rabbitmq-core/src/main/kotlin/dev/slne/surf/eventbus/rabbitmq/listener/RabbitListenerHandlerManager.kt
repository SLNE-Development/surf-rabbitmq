@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.eventbus.rabbitmq.listener

import com.rabbitmq.client.AMQP
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitProtocolVersionMismatchException
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.api.version.RabbitMqVersion
import dev.slne.surf.eventbus.rabbitmq.common.connection.consumer.RabbitAck
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketPropertiesInjector
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.eventbus.rabbitmq.common.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.rabbitmq.shared.serialization.KotlinSerializerNameCache
import dev.slne.surf.eventbus.rabbitmq.core.connection.RabbitConnectionImpl
import dev.slne.surf.eventbus.rabbitmq.rpc.RabbitRpcServiceImpl
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Deserializes incoming request envelopes and dispatches them to the RPC service.
 *
 * `RpcCallRequestPacket` is the only request type on the wire now that the untyped packet API
 * is gone, so this no longer needs the reflection-based multi-handler registry the
 * `@RabbitHandler` mechanism used to provide — there is exactly one destination, wired directly.
 */
class RabbitListenerHandlerManager(
    private val api: SurfRabbitApi,
    private val connection: RabbitConnectionImpl
) {
    private val requestSerializerCache =
        KotlinSerializerNameCache<RabbitRequestPacket<*>>(api.cbor.serializersModule).apply {
            register(RpcCallRequestPacket::class.java)
        }

    private val serializerCache =
        KotlinSerializerCache<RabbitResponsePacket>(api.cbor.serializersModule)

    companion object {
        private val log = logger()
    }

    fun hasHandlers(): Boolean = api.rpcService.hasRegisteredServices()

    /** Whether a failed request is retried before being dead-lettered. Always true: the only
     *  remaining request type, RPC/@FireAndForget calls, has no per-callable opt-out. */
    fun retryEnabledFor(requestClass: Class<*>): Boolean = true

    /**
     * Moves a message whose handler failed onto the retry ladder (or the dead-letter queue),
     * then acks the original delivery.
     *
     * Falls back to `nack(requeue = false)` if the republish itself throws (for instance the
     * broker went away mid-republish): the origin queue's own dead-letter exchange preserves
     * the message, so losing a retry rung is acceptable, losing the message is not.
     */
    private suspend fun retryOrDeadLetter(
        requestClass: Class<*>,
        body: ByteArray,
        properties: AMQP.BasicProperties,
        originQueue: String,
        ack: RabbitAck
    ) {
        try {
            connection.retryPublisher.handleFailure(
                body = body,
                properties = properties,
                originQueue = originQueue,
                serviceName = api.identity.serviceName,
                retryEnabled = retryEnabledFor(requestClass),
                rechunkAsRequest = true
            )
            ack.ack()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.atSevere()
                .withCause(t)
                .log("Failed to republish failed request of type ${requestClass.name} to the retry ladder, falling back to nack")
            ack.nack(requeue = false)
        }
    }

    suspend fun handleRequest(
        correlationId: String,
        replyTo: String?,
        body: ByteArray,
        ack: RabbitAck,
        properties: AMQP.BasicProperties,
        originQueue: String,
        senderVersion: RabbitMqVersion = RabbitMqVersion.UNKNOWN
    ) {
        val request = try {
            RabbitPacketSerializer.deserializeRequest(api, body, requestSerializerCache)
        } catch (e: SurfRabbitProtocolVersionMismatchException) { // TODO: correctly handle protocol version mismatch
            log.atWarning()
                .withCause(e)
                .log("Protocol version mismatch, discarding request")
            ack.nack(requeue = false)
            return
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Failed to deserialize request envelope, discarding message")
            ack.nack(requeue = false)
            return
        }

        if (request !is RpcCallRequestPacket) {
            log.atWarning()
                .log("No handler found for request of type ${request.javaClass.name}, discarding message")
            ack.nack(requeue = false)
            return
        }

        val requestJob = Job(api.scope.coroutineContext.job)
        try {
            val handlerScope = api.scope + requestJob
            RabbitPacketPropertiesInjector.inject(request, handlerScope, senderVersion)

            val handlerJob = handlerScope.launch {
                (api.rpcService as RabbitRpcServiceImpl).handleRequest(request)
            }

            if (replyTo == null) {
                // Fire-and-forget: done when the handler is done. There is no response to
                // wait for and nobody to send one to. Awaiting responseDeferred here (the
                // RPC path below) would time out after requestTimeoutSeconds for every
                // handler that never calls respond() - which a F&F handler naturally never
                // does - nacking and eventually retrying work that already succeeded.
                val failure = AtomicReference<Throwable?>(null)
                handlerJob.invokeOnCompletion { cause ->
                    if (cause != null && cause !is CancellationException) failure.set(cause)
                }
                handlerJob.join()

                val cause = failure.get()
                if (cause == null) {
                    if (request.hasResponded()) {
                        log.atFine().log(
                            "Fire-and-forget handler for %s called respond(); the response is discarded",
                            request.javaClass.name
                        )
                    }
                    ack.ack()
                } else {
                    log.atSevere().withCause(cause)
                        .log("Fire-and-forget handler for %s failed", request.javaClass.name)
                    retryOrDeadLetter(request.javaClass, body, properties, originQueue, ack)
                }
                return
            }

            handlerJob.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    log.atSevere()
                        .withCause(cause)
                        .log("Error in handler for request of type ${request.javaClass.name}, discarding message")
                    request.responseDeferred.cancel("Error in handler", cause)

                    api.scope.launch {
                        retryOrDeadLetter(request.javaClass, body, properties, originQueue, ack)
                    }
                }
            }

            val requestTimeoutSeconds = api.config.getRequestTimeoutSeconds().seconds
            try {
                val response = withTimeout(requestTimeoutSeconds) {
                    request.responseDeferred.await()
                }
                val responseBytes =
                    RabbitPacketSerializer.serializeResponse(api, serializerCache, response)
                connection.replyToRequest(correlationId, replyTo, ack, responseBytes)
            } catch (e: TimeoutCancellationException) {
                log.atSevere()
                    .log(
                        "Handler for ${request.javaClass.name} did not respond within ${requestTimeoutSeconds}, discarding message"
                    )
                requestJob.cancel("Handler timed out")
                retryOrDeadLetter(request.javaClass, body, properties, originQueue, ack)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.atSevere()
                    .withCause(e)
                    .log("Error handling request of type ${request.javaClass.name}, discarding message")
                retryOrDeadLetter(request.javaClass, body, properties, originQueue, ack)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Error handling request of type ${request.javaClass.name}, discarding message")
            retryOrDeadLetter(request.javaClass, body, properties, originQueue, ack)
        } finally {
            requestJob.cancel("Request handler finished")
            request.responseDeferred.cancel()
        }
    }
}
