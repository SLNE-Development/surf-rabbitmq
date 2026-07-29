package dev.slne.surf.rabbitmq.rpc

import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcCall
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallRequestPacket
import dev.slne.surf.rabbitmq.common.rpc.packet.RpcCallResponsePacket
import dev.slne.surf.rabbitmq.common.rpc.serialization.RpcSerializerCache
import dev.slne.surf.rabbitmq.rpc.service.RpcServiceExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * Dispatches RPC calls made via [SurfRabbitApi.rpc] and hosts implementations registered via
 * [SurfRabbitApi.registerService].
 *
 * Replaces the former `ClientRpcServiceImpl` / `ServerRabbitRpcServiceImpl` split: a single
 * instance handles both directions now, since capability follows from which method a caller
 * uses rather than from a fixed client/server role.
 */
@OptIn(ExperimentalSerializationApi::class)
class RabbitRpcServiceImpl(private val api: SurfRabbitApi) : RabbitRpcService {

    companion object {
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }

    private val serviceIdCounter = AtomicLong(0)
    private val callCounter = AtomicLong(0)
    private val rpcSerializerCache = RpcSerializerCache()

    private val rpcServices = Caffeine.newBuilder()
        .build<String, RpcServiceExecutor<*>>()

    private val internalScope = CoroutineScope(
        api.scope.coroutineContext + SupervisorJob(api.scope.coroutineContext.job)
    )

    override fun <Service : Any> serviceDescriptorOf(kClass: KClass<Service>): RabbitRpcServiceDescriptor<Service> {
        val descriptor = findServiceDescriptor(kClass) ?: error("Unable to find a service descriptor of the $kClass.")

        if (descriptor !is RabbitRpcServiceDescriptor<*>) {
            error("Located service descriptor is not a RabbitRpcServiceDescriptor: $descriptor but $kClass")
        }

        @Suppress("UNCHECKED_CAST")
        return descriptor as RabbitRpcServiceDescriptor<Service>
    }

    private fun <Service : Any> findServiceDescriptor(kClass: KClass<Service>): Any? {
        return ServiceDescriptorCache.get(kClass.java)
    }

    private object ServiceDescriptorCache : ClassValue<Any?>() {
        override fun computeValue(type: Class<*>): Any? {
            if (!type.isInterface) return null
            val packageName = type.packageName
            val simpleName = type.simpleName
            val descriptorFqName = "$packageName.${simpleName}Descriptor"

            return try {
                val descriptorClass = Class.forName(descriptorFqName, false, type.classLoader)
                val descriptorKClass = descriptorClass.kotlin
                descriptorKClass.objectInstance
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
    }

    override fun <Service : Any> createService(serviceKClass: KClass<Service>, service: String?): Service {
        val descriptor = serviceDescriptorOf(serviceKClass)
        val id = serviceIdCounter.incrementAndGet()

        // @RpcService(service = ...) lands in Plan 4; until then the override is mandatory.
        val target = service ?: error(
            "No target service for ${descriptor.fqName}. Pass rpc(service = \"...\")."
        )

        return descriptor.createInstance(id, api, RabbitTarget.ServiceTarget(target))
    }

    override suspend fun <T> call(call: RabbitRpcCall): T {
        val callable = call.descriptor.getCallable(call.callableName)
            ?: error("Unexpected callable '${call.callableName}' for ${call.descriptor.fqName} service")

        val id = callCounter.incrementAndGet()
        val callId = "${callable.name}:$id"
        val serialFormat = api.cbor

        val request = serializeRequest(callId, call, callable, serialFormat)
        val result = api.connection
            .sendRequest(request, RpcCallResponsePacket::class.java, call.target)
            .response

        if (result is RpcCallResponsePacket.RpcCallResponse.Error) {
            // Servers older than 1.6.0 only send the legacy SerializableError.
            throw result.serializedException?.deserialize() ?: result.cause.buildFakeThrowable()
        }

        require(result is RpcCallResponsePacket.RpcCallResponse.Success) { "Unexpected response type: ${result::class}" }

        val serializerResult = rpcSerializerCache.getReturnTypeSerializer(callable, serialFormat.serializersModule)

        @Suppress("UNCHECKED_CAST")
        return decodeRequest(serialFormat, serializerResult, result) as T
    }

    private fun serializeRequest(
        callId: String,
        call: RabbitRpcCall,
        callable: RabbitRpcCallable<*>,
        serialFormat: BinaryFormat
    ): RpcCallRequestPacket {
        val data = if (callable.parameters.isNotEmpty()) {
            val parametersSerializer =
                rpcSerializerCache.getParameterSerializer(callable, serialFormat.serializersModule)
            serialFormat.encodeToByteArray(parametersSerializer, call.arguments)
        } else {
            EMPTY_BYTE_ARRAY
        }

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

    @RabbitHandler
    suspend fun handleRequest(request: RpcCallRequestPacket) {
        val service = rpcServices.getIfPresent(request.rpcServiceFqName)
        if (service != null) {
            service.accept(request)
            return
        }

        request.respond(
            rpcErrorResponse(
                NoSuchMethodError("No Service with fq '${request.rpcServiceFqName}' found"),
                request.senderVersion
            )
        )
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

    override fun hasRegisteredServices(): Boolean = rpcServices.asMap().isNotEmpty()
}
