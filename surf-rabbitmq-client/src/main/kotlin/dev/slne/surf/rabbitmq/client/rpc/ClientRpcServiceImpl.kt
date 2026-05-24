package dev.slne.surf.rabbitmq.client.rpc

import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import dev.slne.surf.rabbitmq.api.rpc.ClientRabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import dev.slne.surf.rabbitmq.common.rpc.CommonRabbitRpcServiceImpl
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcCall
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallResponsePacket
import dev.slne.surf.rabbitmq.common.rpc.serialization.CallableParametersSerializer
import dev.slne.surf.rabbitmq.common.rpc.serialization.buildContextual
import kotlinx.coroutines.isActive
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class ClientRpcServiceImpl(private val api: ClientRabbitMQApi) : CommonRabbitRpcServiceImpl(api),
    ClientRabbitRpcService {
    private val serviceIdCounter = AtomicLong(0)
    private val callCounter = AtomicLong(0)

    override fun <Service : Any> createService(serviceKClass: KClass<Service>): Service {
        val descriptor = serviceDescriptorOf(serviceKClass)
        val id = serviceIdCounter.incrementAndGet()

        return descriptor.createInstance(id, api)
    }

    private fun isClientActive() = api.scope.isActive

    override suspend fun <T> call(call: RabbitRpcCall): T {
        val callable = call.descriptor.getCallable(call.callableName)
            ?: error("Unexpected callable '${call.callableName}' for ${call.descriptor.fqName} service")

        val id = callCounter.incrementAndGet()
        val callId = "${callable.name}:$id"
        val serialFormat = api.cbor

        val request = serializeRequest(callId, call, callable, serialFormat)
        val result = api.sendRequest(request).response

        if (result is RpcCallResponsePacket.RpcCallResponse.Error) {
            throw result.cause.buildFakeThrowable()
        }

        require(result is RpcCallResponsePacket.RpcCallResponse.Success) { "Unexpected response type: ${result::class}" }

        val serializerResult = serialFormat.serializersModule
            .buildContextual(callable.returnType)

        @Suppress("UNCHECKED_CAST")
        return decodeRequest(serialFormat, serializerResult, result) as T
    }

    private fun serializeRequest(
        callId: String,
        call: RabbitRpcCall,
        callable: RabbitRpcCallable<*>,
        serialFormat: BinaryFormat
    ): RpcCallRequestPacket {
        val parametersSerializer = CallableParametersSerializer(callable, serialFormat.serializersModule)
        val data = serialFormat.encodeToByteArray(parametersSerializer, call.arguments)

        return RpcCallRequestPacket(
            rpcCallId = callId,
            rpcServiceFqName = call.descriptor.fqName,
            rpcCallableName = callable.name,
            data = data,
            rpcServiceId = call.serviceId
        )
    }

    private fun <T> decodeRequest(
        serialFormat: BinaryFormat,
        dataSerializer: KSerializer<T>,
        response: RpcCallResponsePacket.RpcCallResponse.Success
    ): T {
        return serialFormat.decodeFromByteArray(dataSerializer, response.data)
    }
}