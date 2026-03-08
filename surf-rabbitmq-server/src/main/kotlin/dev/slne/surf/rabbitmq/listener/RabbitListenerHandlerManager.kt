package dev.slne.surf.rabbitmq.listener

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
import dev.slne.surf.surfapi.core.api.util.logger
import dev.slne.surf.surfapi.core.api.util.mutableObject2ObjectMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.seconds

class RabbitListenerHandlerManager(private val api: RabbitMQApi, private val connection: ServerRabbitMQConnectionImpl) {
    private val handlers = mutableObject2ObjectMapOf<Class<*>, RabbitListenerHandler>()
    private val registrationLock = ReentrantReadWriteLock()

    private val requestSerializerCache = KotlinSerializerNameCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)
    private val serializerCache = KotlinSerializerCache<RabbitResponsePacket>(api.cbor.serializersModule)

    fun registerRequestHandler(instance: Any) {
        if (api.isFrozen()) throw SurfRabbitApiAlreadyFrozenException()

        for (method in instance.javaClass.declaredMethods) {
            if (!method.isAnnotationPresent(RabbitHandler::class.java)) continue

            if (method.parameterCount != 1) {
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
            if (!RabbitListenerHandlerFactory.canAccess(instance, method)) {
                throw SurfRabbitHandlerNotAccessibleException(
                    method.name,
                    handlerClass.name,
                    handlerClass.packageName
                )
            }

            @Suppress("UNCHECKED_CAST")
            parameterType as Class<out RabbitRequestPacket<*>>
            requestSerializerCache.register(parameterType)

            val handler = RabbitListenerHandlerFactory.create(instance, method, parameterType)
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

    fun handleRequest(correlationId: String, replyTo: String, body: ByteArray, deliveryTag: ULong) {
        val request = try {
            RabbitPacketSerializer.deserializeRequest(api, body, requestSerializerCache)
        } catch (e: SurfRabbitProtocolVersionMismatchException) {
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

        RabbitPacketPropertiesInjector.inject(request, api.scope)

        try {
            handler.handle(request)
        } catch (e: Throwable) {
            log.atSevere()
                .withCause(e)
                .log("Error handling request of type ${request.javaClass.name}, discarding message")
            connection.nackRequest(deliveryTag)
            return
        }

        api.scope.launch {
            val requestTimeoutSeconds = api.config.requestTimeout
            try {
                val response = withTimeout(requestTimeoutSeconds.seconds) {
                    request.responseDeferred.await()
                }
                val responseBytes = RabbitPacketSerializer.serializeResponse(api, serializerCache, response)
                connection.replyToRequest(correlationId, replyTo, deliveryTag, responseBytes)
            } catch (e: TimeoutCancellationException) {
                log.atSevere()
                    .log(
                        "Handler for ${request.javaClass.name} did not respond within ${requestTimeoutSeconds}s, discarding message"
                    )
                connection.nackRequest(deliveryTag)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.atSevere()
                    .withCause(e)
                    .log("Error handling request of type ${request.javaClass.name}, discarding message")
                connection.nackRequest(deliveryTag)
            }
        }
    }

    companion object {
        private val log = logger()
    }
}