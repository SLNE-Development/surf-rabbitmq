package dev.slne.surf.rabbitmq.rpc.service

import dev.slne.surf.api.core.util.toSerializableError
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallResponsePacket
import dev.slne.surf.rabbitmq.common.rpc.serialization.CallableParametersSerializer
import dev.slne.surf.rabbitmq.common.rpc.serialization.buildContextual
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
    private val descriptor: RabbitRpcServiceDescriptor<T>,
    private val serverScope: CoroutineScope,
    private val serialFormat: BinaryFormat
) {
    companion object {
        private val unitKType = typeOf<Unit>()
    }

    private val logger = ComponentLogger.logger(service.javaClass)

    suspend fun accept(request: RpcCallRequestPacket) {
        try {
            processMessage(request)
        } catch (e: Throwable) {
            if (!request.hasResponded()) {
                request.respond(RpcCallResponsePacket(RpcCallResponsePacket.RpcCallResponse.Error(e.toSerializableError())))
            }

            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }

            logger.error("Error processing RPC call '${request.rpcCallId}' in service '${service.javaClass.name}'", e)
        }
    }

    private suspend fun processMessage(request: RpcCallRequestPacket) {
        val callId = request.rpcCallId
        val callableName = request.rpcCallableName
        val callable = descriptor.getCallable(callableName)
            ?: error("Service '${service.javaClass.name}' has no method '$callableName'! Are the service and client versions in sync?")

        val parametersSerializer = CallableParametersSerializer(callable, serialFormat.serializersModule)
        val data = serialFormat.decodeFromByteArray(parametersSerializer, request.data)

        var failure: Throwable? = null

        try {
            val value = callable.invoker.call(service, data).let { intercepted ->
                if (callable.returnType.kType == unitKType) {
                    Unit
                } else {
                    intercepted
                }
            }

            val returnSerializer = serialFormat.serializersModule
                .buildContextual(callable.returnType)

            sendResponse(serialFormat, returnSerializer, value, request)
        } catch (e: CancellationException) {
            failure = e
            serverScope.ensureActive()
        } catch (e: Throwable) {
            failure = e
            logger.error("Error processing RPC call $callId in service '${service.javaClass.name}'" , e)
        } finally {
            if (failure != null) {
                request.respond(RpcCallResponsePacket(RpcCallResponsePacket.RpcCallResponse.Error(failure.toSerializableError())))
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