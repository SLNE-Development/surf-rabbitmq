package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.eventbus.service.ServiceCallable
import dev.slne.surf.eventbus.rabbitmq.rpc.descriptor.RpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.eventbus.rabbitmq.rpc.packet.RpcCallResponsePacket
import dev.slne.surf.eventbus.service.serialization.ServiceSerializerCache
import dev.slne.surf.eventbus.rabbitmq.rpc.rpcErrorResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.typeOf

class RpcServiceExecutor<T : Any>(
    val service: T,
    private val descriptor: RpcServiceDescriptor<T>,
    private val serverScope: CoroutineScope,
    private val serialFormat: BinaryFormat
) {
    companion object {
        private val unitKType = typeOf<Unit>()
        private val EMPTY_ANY_ARRAY = emptyArray<Any?>()
    }

    private val logger = ComponentLogger.logger(service.javaClass)
    private val rpcSerializerCache = ServiceSerializerCache()

    suspend fun accept(request: RpcCallRequestPacket) {
        val callable = descriptor.getCallable(request.rpcCallableName)

        try {
            processMessage(request, callable)
        } catch (e: Throwable) {
            if (callable?.fireAndForget == true) {
                // No reply channel exists for this call. Rethrowing (rather than encoding the
                // failure into a response nobody reads) lets the caller -
                // RabbitListenerHandlerManager.handleRequest - take the retry ladder.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                throw e
            }

            if (!request.hasResponded()) {
                request.respond(rpcErrorResponse(e, request.senderVersion))
            }

            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }

            logger.error("Error processing RPC call '${request.rpcCallId}' in service '${service.javaClass.name}'", e)
        }
    }

    private suspend fun processMessage(request: RpcCallRequestPacket, callableArg: ServiceCallable<T>?) {
        val callId = request.rpcCallId
        val callableName = request.rpcCallableName
        val callable = callableArg
            ?: error("Service '${service.javaClass.name}' has no method '$callableName'! Are the service and client versions in sync?")

        val data = if (callable.parameters.isNotEmpty()) {
            val parametersSerializer =
                rpcSerializerCache.getParameterSerializer(callable, serialFormat.serializersModule)
            serialFormat.decodeFromByteArray(parametersSerializer, request.data)
        } else {
            EMPTY_ANY_ARRAY
        }

        if (callable.fireAndForget) {
            // A thrown exception here propagates straight out of accept()'s try, which
            // rethrows it for a fire-and-forget callable - see the fireAndForget branch there.
            callable.invoker.call(service, data)
            return
        }

        var failure: Throwable? = null

        try {
            val value = callable.invoker.call(service, data).let { intercepted ->
                if (callable.returnType.kType == unitKType) {
                    Unit
                } else {
                    intercepted
                }
            }

            val returnSerializer = rpcSerializerCache.getReturnTypeSerializer(callable, serialFormat.serializersModule)

            sendResponse(serialFormat, returnSerializer, value, request)
        } catch (e: CancellationException) {
            failure = e
            serverScope.ensureActive()
        } catch (e: Throwable) {
            failure = e
            logger.error("Error processing RPC call $callId in service '${service.javaClass.name}'", e)
        } finally {
            if (failure != null) {
                request.respond(rpcErrorResponse(failure, request.senderVersion))
            }
        }
    }

    private fun sendResponse(
        serialFormat: BinaryFormat,
        returnSerializer: KSerializer<Any?>,
        value: Any?,
        request: RpcCallRequestPacket
    ) {
        val serializedValue = serialFormat.encodeToByteArray(returnSerializer, value)
        val response = RpcCallResponsePacket.RpcCallResponse.Success(serializedValue)
        request.respond(RpcCallResponsePacket(response))
    }
}