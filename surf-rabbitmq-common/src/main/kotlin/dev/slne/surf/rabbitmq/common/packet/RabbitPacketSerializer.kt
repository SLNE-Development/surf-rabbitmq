package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolVersionMismatchException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import kotlinx.serialization.*

@OptIn(ExperimentalSerializationApi::class)
object RabbitPacketSerializer {
    fun serializeRequest(
        api: RabbitMQApi,
        serializer: KSerializer<RabbitRequestPacket<*>>,
        request: RabbitRequestPacket<*>
    ): ByteArray {
        val envelope = RabbitRequestEnvelope(
            request.javaClass.name,
            api.protocolVersion,
            api.cbor.encodeToByteArray(serializer, request)
        )

        return api.cbor.encodeToByteArray<RabbitRequestEnvelope>(envelope)
    }

    @Suppress("UNCHECKED_CAST")
    fun deserializeRequest(
        api: RabbitMQApi,
        byteArray: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitRequestPacket<*>>,
    ): RabbitRequestPacket<*> {
        val envelope = api.cbor.decodeFromByteArray<RabbitRequestEnvelope>(byteArray)

        val version = envelope.version
        if (version != api.protocolVersion) {
            throw SurfRabbitProtocolVersionMismatchException(api.protocolVersion, version)
        }

        val serializer = serializerCache.get(envelope.className)
            ?: throw SurfRabbitSerializerNotFoundException(envelope.className)

        return api.cbor.decodeFromByteArray(serializer, envelope.body)
    }

    @Suppress("UNCHECKED_CAST")
    fun serializeResponse(
        api: RabbitMQApi,
        serializerCache: KotlinSerializerCache<RabbitResponsePacket>,
        responsePacket: RabbitResponsePacket
    ): ByteArray {
        val serializer = serializerCache.get(responsePacket.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(responsePacket.javaClass.name)

        val envelope = RabbitResponseEnvelope(
            responsePacket.javaClass.name,
            api.cbor.encodeToByteArray(serializer, responsePacket)
        )
        return api.cbor.encodeToByteArray<RabbitResponseEnvelope>(envelope)
    }

    fun deserializeResponse(
        api: RabbitMQApi,
        response: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitResponsePacket>
    ): RabbitResponsePacket {
        val envelope = api.cbor.decodeFromByteArray<RabbitResponseEnvelope>(response)
        val serializer = serializerCache.get(envelope.responseClass)
            ?: throw SurfRabbitSerializerNotFoundException(envelope.responseClass)
        return api.cbor.decodeFromByteArray(serializer, envelope.body)
    }

    @Serializable
    data class RabbitResponseEnvelope(
        val responseClass: String,
        val body: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RabbitResponseEnvelope

            if (responseClass != other.responseClass) return false
            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = responseClass.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    @Serializable
    data class RabbitRequestEnvelope(val className: String, val version: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RabbitRequestEnvelope

            if (className != other.className) return false
            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = className.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }
}