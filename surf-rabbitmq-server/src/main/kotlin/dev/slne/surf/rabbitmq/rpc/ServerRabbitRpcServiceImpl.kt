package dev.slne.surf.rabbitmq.rpc

import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.api.core.util.toSerializableError
import dev.slne.surf.rabbitmq.api.ServerRabbitMQApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcCall
import dev.slne.surf.rabbitmq.api.rpc.ServerRabbitRpcService
import dev.slne.surf.rabbitmq.common.rpc.CommonRabbitRpcServiceImpl
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallResponsePacket
import dev.slne.surf.rabbitmq.rpc.service.RpcServiceExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

@OptIn(ExperimentalSerializationApi::class)
class ServerRabbitRpcServiceImpl(private val api: ServerRabbitMQApi) : CommonRabbitRpcServiceImpl(api),
    ServerRabbitRpcService {
    private val rpcServices = Caffeine.newBuilder()
        .build<String, RpcServiceExecutor<*>>()

    private val internalScope = CoroutineScope(
        api.scope.coroutineContext + SupervisorJob(api.scope.coroutineContext.job)
    )

    override suspend fun <T> call(call: RabbitRpcCall): T {
        throw NotImplementedError("Can not execute RPC call on server side.")
    }

    @RabbitHandler
    suspend fun handleRequest(request: RpcCallRequestPacket) {
        val service = rpcServices.getIfPresent(request.rpcServiceFqName)
        if (service != null) {
            service.accept(request)
            return
        }

        request.respond(RpcCallResponsePacket(RpcCallResponsePacket.RpcCallResponse.Error(NoSuchMethodError("No Service with fq '${request.rpcServiceFqName}' found").toSerializableError())))
    }

    override fun <Service : Any> registerService(
        serviceKClass: KClass<Service>,
        serviceInstance: Service
    ) {
        val descriptor = serviceDescriptorOf(serviceKClass)
        val executor = RpcServiceExecutor(
            serviceInstance,
            descriptor,
            internalScope,
            api.cbor
        )

        val previous = rpcServices.asMap().putIfAbsent(descriptor.fqName, executor)
        require(previous == null) { "Service with fq '${descriptor.fqName}' already registered" }
    }

    override fun <Service : Any> unregisterService(serviceKClass: KClass<Service>) {
        val descriptor = serviceDescriptorOf(serviceKClass)
        rpcServices.invalidate(descriptor.fqName)
    }
}