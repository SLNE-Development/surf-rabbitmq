@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.wrapper

import dev.slne.surf.surfapi.core.api.serializer.SurfSerializerModule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoBuf

object RabbitMQSerializer {
    private val serializerModule = SerializersModule {
        include(SurfSerializerModule.all)
    }

    val PROTO = ProtoBuf {
        serializersModule = serializerModule
    }
}