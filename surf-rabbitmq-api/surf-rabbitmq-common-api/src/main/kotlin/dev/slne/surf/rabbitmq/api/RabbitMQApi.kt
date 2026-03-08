package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import dev.slne.surf.surfapi.core.api.serializer.SurfSerializerModule
import dev.slne.surf.surfapi.core.api.util.logger
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import org.jetbrains.annotations.MustBeInvokedByOverriders

@OptIn(ExperimentalSerializationApi::class)
abstract class RabbitMQApi @InternalRabbitMQ constructor(
    @InternalRabbitMQ val config: RabbitMQConfig,
    @InternalRabbitMQ val pluginName: String,
    val protocolVersion: Int,
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

    companion object {
        private val log = logger()

        @InternalRabbitMQ
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                include(SurfSerializerModule.all.overwriteWith(additionalSerializerModule))
            }
        }
    }
}