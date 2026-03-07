@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.wrapper

import dev.slne.surf.rabbitmq.wrapper.packet.RabbitPacket
import dev.slne.surf.surfapi.core.api.serializer.SurfSerializerModule
import dev.slne.surf.surfapi.core.api.util.freeze
import dev.slne.surf.surfapi.core.api.util.mutableObjectSetOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

object RabbitMQSerializer {
    private val _packets = mutableObjectSetOf<KClass<RabbitPacket>>()
    val packets get() = _packets.freeze()

    private lateinit var packetSerializerModule: SerializersModule

    lateinit var serializerModule: SerializersModule
        private set
    
    lateinit var CBOR: Cbor
        private set

    @OptIn(InternalSerializationApi::class)
    internal fun buildSerializerModule() {
        packetSerializerModule = SerializersModule {
            polymorphic(RabbitPacket::class) {
                packets.forEach { packetClass ->
                    subclass(packetClass, packetClass.serializer())
                }
            }
        }

        serializerModule = SerializersModule {
            include(SurfSerializerModule.all)
            include(packetSerializerModule)
        }

        CBOR = Cbor {
            serializersModule = serializerModule
        }
    }
}