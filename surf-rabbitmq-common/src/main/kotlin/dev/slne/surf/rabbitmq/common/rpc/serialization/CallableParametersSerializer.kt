package dev.slne.surf.rabbitmq.common.rpc.serialization

import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule

class CallableParametersSerializer(
    private val callable: RabbitRpcCallable<*>,
    private val module: SerializersModule
) : KSerializer<Array<Any?>> {
    private val callableSerializers = Array(callable.parameters.size) { i ->
        module.buildContextual(callable.parameters[i].type)
    }

    override val descriptor = buildClassSerialDescriptor("surf.rabbitmq.CallableParametersSerializer") {
        for ((index, serializer) in callableSerializers.withIndex()) {
            val parameter = callable.parameters[index]
            element(
                elementName = parameter.name,
                descriptor = serializer.descriptor,
                annotations = parameter.type.annotations,
                isOptional = parameter.isOptional
            )
        }
    }


    override fun serialize(encoder: Encoder, value: Array<Any?>) = encoder.encodeStructure(descriptor) {
        for (i in callable.parameters.indices) {
            encodeSerializableElement(descriptor, i, callableSerializers[i], value[i])
        }
    }

    override fun deserialize(decoder: Decoder): Array<Any?> = decoder.decodeStructure(descriptor) {
        val result = arrayOfNulls<Any?>(callable.parameters.size)
        val seen = BooleanArray(callable.parameters.size)

        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) {
                break
            }

            result[index] = decodeSerializableElement(descriptor, index, callableSerializers[index])
            seen[index] = true
        }

        for (i in callable.parameters.indices) {
            val parameter = callable.parameters[i]

            require(seen[i]) {
                "Missing RPC argument '${parameter.name}' for callable '${callable.name}'"
            }
        }

        result
    }
}