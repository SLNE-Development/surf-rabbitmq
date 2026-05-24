package dev.slne.surf.rabbitmq.api

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.standard.response.StringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.optional.OptionalStringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.OptionalPrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.PrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.ArrayResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.OptionalArrayResponse
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import org.jetbrains.annotations.MustBeInvokedByOverriders
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
abstract class RabbitMQApi @InternalRabbitMQ constructor(
    @InternalRabbitMQ val config: RabbitMQConfig,
    @InternalRabbitMQ val pluginName: String,
    val cbor: Cbor
) {
    @InternalRabbitMQ
    val scope =
        CoroutineScope(Dispatchers.Default + CoroutineName("RabbitMQApi-$pluginName") + SupervisorJob() + CoroutineExceptionHandler { context, throwable ->
            log.atSevere()
                .withCause(throwable)
                .log("Unhandled exception in RabbitMQApi coroutine ${context[CoroutineName]}")
        })

    open val connection = RabbitMQConnection.create(this)

    @InternalRabbitMQ
    open val rpcService = RabbitRpcServiceFactory.instance.createRpcService(this)

    private var frozen = false

    @MustBeInvokedByOverriders
    open suspend fun connect() {
        if (!isFrozen()) throw SurfRabbitApiNotFrozenException()

        connection.connect()
    }

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    @MustBeInvokedByOverriders
    open suspend fun disconnect() {
        connection.disconnect()

        scope.cancel("RabbitMQApi disconnected")
    }

    fun freeze() {
        if (isFrozen()) throw SurfRabbitApiAlreadyFrozenException()
        frozen = true
    }

    fun isFrozen(): Boolean = frozen

    inline fun <reified Service : Any> serviceDescriptorOf(): RabbitRpcServiceDescriptor<Service> {
        return rpcService.serviceDescriptorOf(Service::class)
    }

    fun <Service : Any> serviceDescriptorOf(kClass: KClass<Service>): RabbitRpcServiceDescriptor<Service> {
        return rpcService.serviceDescriptorOf(kClass)
    }

    companion object {
        private val log = logger()

        @InternalRabbitMQ
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                include(SurfSerializerModule.all.overwriteWith(additionalSerializerModule))
                include(defaultSerializersModule)
            }
        }

        private val defaultSerializersModule = SerializersModule {
            include(PrimitiveResponse.SERIALIZER_MODULE)
            include(OptionalPrimitiveResponse.SERIALIZER_MODULE)
            include(ArrayResponse.SERIALIZER_MODULE)
            include(OptionalArrayResponse.SERIALIZER_MODULE)

            contextual(StringResponsePacket.serializer())
            contextual(OptionalStringResponsePacket.serializer())
        }
    }
}