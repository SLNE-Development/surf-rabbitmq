package dev.slne.surf.rabbitmq.listener

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.exception.ProtocolVersionMismatchException
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketPropertiesInjector
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import dev.slne.surf.rabbitmq.connection.ServerRabbitMQConnectionImpl
import dev.slne.surf.surfapi.core.api.util.logger
import dev.slne.surf.surfapi.core.api.util.mutableObject2ObjectMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class RabbitListenerHandlerManager(private val api: RabbitMQApi, private val connection: ServerRabbitMQConnectionImpl) {
    private val handlers = mutableObject2ObjectMapOf<Class<*>, RabbitListenerHandler>()
    private val registrationLock = ReentrantReadWriteLock()

    private val requestSerializerCache = KotlinSerializerNameCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)
    private val serializerCache = KotlinSerializerCache<RabbitResponsePacket>(api.cbor.serializersModule)

    fun registerRequestHandler(instance: Any) {
        require(!api.isFrozen()) { "Cannot register request handlers after the API has been frozen" }

        for (method in instance.javaClass.declaredMethods) {
            if (!method.isAnnotationPresent(RabbitHandler::class.java)) continue

            if (method.parameterCount != 1) {
                error("Listener functions must have exactly one parameter: ${instance::class.qualifiedName}::${method.name}")
            }

            val parameterType = method.parameterTypes[0]

            if (!parameterType.isAssignableFrom(RabbitRequestPacket::class.java)) {
                error("Listener function parameter must be a subclass of RabbitPacket: ${instance::class.qualifiedName}::${method.name}")
            }

            val handlerClass = method.declaringClass
            if (!RabbitListenerHandlerFactory.canAccess(instance, method)) {
                error(
                    "Method ${method.name} in ${handlerClass.name} is not accessible via privateLookupIn " +
                            "— ensure the package '${handlerClass.packageName}' is opened to the surf-redis module. " +
                            "Cannot register as request handler."
                )
            }

            parameterType as Class<out RabbitRequestPacket<*>>
            requestSerializerCache.register(parameterType)

            val handler = RabbitListenerHandlerFactory.create(instance, method, parameterType)
            val current = registrationLock.write { handlers.putIfAbsent(parameterType, handler) }
            if (current != null) {
                error("Duplicate handler for ${parameterType.name}: ${current.javaClass.name} and ${handler.javaClass.name}")
            }
        }
    }

    fun handleRequest(correlationId: String, replyTo: String, body: ByteArray, deliveryTag: ULong) {
        try {
            val request = RabbitPacketSerializer.deserializeRequest(api, body, requestSerializerCache)
            val handler = handlers[request.javaClass] ?: return run {
                log.atWarning()
                    .log("No handler found for request of type ${request.javaClass.name}, ignoring")
            }

            RabbitPacketPropertiesInjector.inject(request, api.scope)

            try {
                handler.handle(request)
            } catch (e: Throwable) {
                log.atSevere()
                    .withCause(e)
                    .log("Error handling request of type ${request.javaClass.name}")
                return
            }

            api.scope.launch {
                try {
                    val response = request.responseDeferred.await()
                    val responseBytes = RabbitPacketSerializer.serializeResponse(api, serializerCache, response)

                    connection.replyToRequest(correlationId, replyTo, deliveryTag, responseBytes)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    log.atSevere()
                        .withCause(e)
                        .log("Error handling request of type ${request.javaClass.name}")
                }
            }
        } catch (e: ProtocolVersionMismatchException) {
            log.atWarning()
                .withCause(e)
                .log("Protocol version mismatch, ignoring request")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.atSevere()
                .withCause(e)
                .log("Error handling request")
        }
    }

    companion object {
        private val log = logger()
    }
}