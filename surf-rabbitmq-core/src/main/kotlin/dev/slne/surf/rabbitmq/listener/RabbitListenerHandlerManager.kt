@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("InternalApiUsage")

package dev.slne.surf.rabbitmq.listener

import dev.slne.surf.api.core.invoker.HiddenInvokerUtil
import dev.slne.surf.api.core.invoker.InvokerFactory
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.core.util.mutableObject2ObjectMapOf
import dev.slne.surf.api.shared.api.util.InternalInvokerApi
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.exception.*
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.version.RabbitMqVersion
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitAck
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketPropertiesInjector
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.shared.serialization.KotlinSerializerCache
import dev.slne.surf.rabbitmq.shared.serialization.KotlinSerializerNameCache
import dev.slne.surf.rabbitmq.shared.dispatch.HandlerMethodHandleProvider
import dev.slne.surf.rabbitmq.shared.dispatch.HandlerTemplate
import dev.slne.surf.rabbitmq.core.connection.RabbitConnectionImpl
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.seconds

@Suppress("UnstableApiUsage")
@OptIn(InternalInvokerApi::class)
class RabbitListenerHandlerManager(
    private val api: SurfRabbitApi,
    private val connection: RabbitConnectionImpl
) {
    private val handlers = mutableObject2ObjectMapOf<Class<*>, RabbitListenerHandler>()
    private val registrationLock = ReentrantReadWriteLock()

    // The RPC dispatcher (registered below, in init) is always present so RpcCallRequestPacket
    // has somewhere to go, but its presence alone must not make hasHandlers() report true for
    // a process that registered nothing itself - registerService() never touches this manager,
    // so hasRegisteredServices() is checked alongside this count.
    private val explicitHandlerCount = AtomicInteger(0)

    private val requestSerializerCache =
        KotlinSerializerNameCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)

    private val serializerCache =
        KotlinSerializerCache<RabbitResponsePacket>(api.cbor.serializersModule)

    companion object {
        private val log = logger()
        private val HANDLER_FACTORY = InvokerFactory(
            /* templateClass = */ HandlerTemplate::class.java,
            /* invokerInterface = */ RabbitListenerHandler::class.java,
            /* lookup = */ HandlerMethodHandleProvider.LOOKUP
        )
    }

    init {
        registerHandler(api.rpcService, countAsExplicit = false)
    }

    fun hasHandlers(): Boolean =
        explicitHandlerCount.get() > 0 || api.rpcService.hasRegisteredServices()

    fun registerRequestHandler(instance: Any) {
        if (api.isFrozen()) throw SurfRabbitApiAlreadyFrozenException()
        registerHandler(instance, countAsExplicit = true)
    }

    private fun registerHandler(instance: Any, countAsExplicit: Boolean) {
        for (method in instance.javaClass.declaredMethods) {
            if (!method.isAnnotationPresent(RabbitHandler::class.java)) continue

            val validParamCount = when {
                HiddenInvokerUtil.isSuspendFunction(method) -> 2
                else -> 1
            }
            if (method.parameterCount != validParamCount) {
                throw SurfRabbitInvalidHandlerParameterCountException(
                    instance::class.qualifiedName ?: instance.javaClass.name,
                    method.name,
                    method.parameterCount
                )
            }

            val parameterType = method.parameterTypes[0]

            if (!RabbitRequestPacket::class.java.isAssignableFrom(parameterType)) {
                throw SurfRabbitInvalidHandlerParameterTypeException(
                    instance::class.qualifiedName ?: instance.javaClass.name,
                    method.name,
                    parameterType.name
                )
            }

            val handlerClass = method.declaringClass
            if (!HANDLER_FACTORY.canAccess(instance, method)) {
                throw SurfRabbitHandlerNotAccessibleException(
                    method.name,
                    handlerClass.name,
                    handlerClass.packageName
                )
            }

            @Suppress("UNCHECKED_CAST")
            parameterType as Class<out RabbitRequestPacket<*>>
            requestSerializerCache.register(parameterType)

            val handler = HANDLER_FACTORY.create(instance, method, parameterType)
            val current = registrationLock.write { handlers.putIfAbsent(parameterType, handler) }
            if (current != null) {
                throw SurfRabbitDuplicateHandlerException(
                    parameterType.name,
                    current.javaClass.name,
                    handler.javaClass.name
                )
            }
        }

        if (countAsExplicit) explicitHandlerCount.incrementAndGet()
    }

    suspend fun handleRequest(
        correlationId: String,
        replyTo: String?,
        body: ByteArray,
        ack: RabbitAck,
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

        val handler = handlers[request.javaClass]
        if (handler == null) {
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
                handler.handle(request)
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
                    // Plan 4 replaces this nack with the retry ladder.
                    ack.nack(requeue = false)
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
                        ack.nack(requeue = false)
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
                ack.nack(requeue = false)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.atSevere()
                    .withCause(e)
                    .log("Error handling request of type ${request.javaClass.name}, discarding message")
                ack.nack(requeue = false)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Error handling request of type ${request.javaClass.name}, discarding message")
            ack.nack(requeue = false)
        } finally {
            requestJob.cancel("Request handler finished")
            request.responseDeferred.cancel()
        }
    }
}
