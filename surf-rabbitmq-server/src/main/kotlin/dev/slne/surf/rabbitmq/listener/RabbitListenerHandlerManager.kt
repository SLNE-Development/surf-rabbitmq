@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.listener

import dev.slne.surf.api.core.invoker.HiddenInvokerUtil
import dev.slne.surf.api.core.invoker.InvokerFactory
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.core.util.mutableObject2ObjectMapOf
import dev.slne.surf.api.shared.api.util.InternalInvokerApi
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.exception.*
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketPropertiesInjector
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import dev.slne.surf.rabbitmq.connection.ServerRabbitMQConnectionImpl
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.seconds

@Suppress("UnstableApiUsage")
@OptIn(InternalInvokerApi::class)
class RabbitListenerHandlerManager(
    private val api: RabbitMQApi,
    private val connection: ServerRabbitMQConnectionImpl
) {
    private val handlers = mutableObject2ObjectMapOf<Class<*>, RabbitListenerHandler>()
    private val registrationLock = ReentrantReadWriteLock()

    private val requestSerializerCache =
        KotlinSerializerNameCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)

    private val serializerCache =
        KotlinSerializerCache<RabbitResponsePacket>(api.cbor.serializersModule)

    companion object {
        private val log = logger()
        private val HANDLER_FACTORY = InvokerFactory(
            /* templateClass = */ RabbitListenerHandlerTemplate::class.java,
            /* invokerInterface = */ RabbitListenerHandler::class.java,
            /* lookup = */ RabbitListenerMethodHandleProvider.LOOKUP
        )
    }

    fun registerRequestHandler(instance: Any) {
        if (api.isFrozen()) throw SurfRabbitApiAlreadyFrozenException()

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
    }

    suspend fun handleRequest(
        correlationId: String,
        replyTo: String,
        body: ByteArray,
        deliveryTag: ULong
    ) {
        val request = try {
            RabbitPacketSerializer.deserializeRequest(api, body, requestSerializerCache)
        } catch (e: SurfRabbitProtocolVersionMismatchException) { // TODO: correctly handle protocol version mismatch
            log.atWarning()
                .withCause(e)
                .log("Protocol version mismatch, discarding request")
            connection.nackRequest(deliveryTag)
            return
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Failed to deserialize request envelope, discarding message")
            connection.nackRequest(deliveryTag)
            return
        }

        val handler = handlers[request.javaClass]
        if (handler == null) {
            log.atWarning()
                .log("No handler found for request of type ${request.javaClass.name}, discarding message")
            connection.nackRequest(deliveryTag)
            return
        }

        val requestJob = Job(api.scope.coroutineContext.job)
        try {
            val handlerScope = api.scope + requestJob
            RabbitPacketPropertiesInjector.inject(request, handlerScope)

            val handlerJob = handlerScope.launch {
                handler.handle(request)
            }

            handlerJob.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    log.atSevere()
                        .withCause(cause)
                        .log("Error in handler for request of type ${request.javaClass.name}, discarding message")
                    request.responseDeferred.cancel("Error in handler", cause)

                    api.scope.launch {
                        connection.nackRequest(deliveryTag)
                    }
                }
            }

            val requestTimeoutSeconds = api.config.requestTimeoutSeconds.seconds
            try {
                val response = withTimeout(requestTimeoutSeconds) {
                    request.responseDeferred.await()
                }
                val responseBytes =
                    RabbitPacketSerializer.serializeResponse(api, serializerCache, response)
                connection.replyToRequest(correlationId, replyTo, deliveryTag, responseBytes)
            } catch (e: TimeoutCancellationException) {
                log.atSevere()
                    .log(
                        "Handler for ${request.javaClass.name} did not respond within ${requestTimeoutSeconds}, discarding message"
                    )
                requestJob.cancel("Handler timed out")
                connection.nackRequest(deliveryTag)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.atSevere()
                    .withCause(e)
                    .log("Error handling request of type ${request.javaClass.name}, discarding message")
                connection.nackRequest(deliveryTag)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Error handling request of type ${request.javaClass.name}, discarding message")
            connection.nackRequest(deliveryTag)
        } finally {
            requestJob.cancel("Request handler finished")
            request.responseDeferred.cancel()
        }
    }
}
