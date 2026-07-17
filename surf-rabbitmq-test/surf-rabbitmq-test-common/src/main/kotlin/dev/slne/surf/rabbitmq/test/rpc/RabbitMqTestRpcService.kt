package dev.slne.surf.rabbitmq.test.rpc

import dev.slne.surf.rabbitmq.api.rpc.RpcService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@RpcService
interface RabbitMqTestRpcService {

    suspend fun getRandomNumber(): Int

    suspend fun doNothing()

    suspend fun customParameterWithCustomReturnType(
        parameter: @Serializable(with = CustomParameterTypeSerializer::class) String
    ): @Serializable(with = CustomReturnTypeSerializer::class) String

    suspend fun annotatedValue(): @RpcMetadata(
        tags = ["stable", "safe"],
        priorities = [1, 2],
        enabled = [true, false],
        grade = RpcMetadataGrade.HIGH,
        marker = '\'',
        ratio = 1.25f
    ) String
}

enum class RpcMetadataGrade {
    LOW,
    HIGH
}

@Target(AnnotationTarget.TYPE)
annotation class RpcMetadata(
    val tags: Array<String>,
    val priorities: IntArray,
    val enabled: BooleanArray,
    val grade: RpcMetadataGrade,
    val marker: Char,
    val ratio: Float
)


object CustomReturnTypeSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("surf.api.CustomReturnType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString("My custom return value encode: $value")
    }

    override fun deserialize(decoder: Decoder): String {
        return "My custom return value decode: ${decoder.decodeString()}"
    }
}

object CustomParameterTypeSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("surf.api.CustomParameterType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString("My custom parameter encode: $value")
    }

    override fun deserialize(decoder: Decoder): String {
        return "My custom parameter decode: ${decoder.decodeString()}"
    }
}
