package dev.slne.surf.rabbitmq.common.rpc.serialization

import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
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
        if (callable.parameters.size != value.size) {
            error("Expected ${callable.parameters.size} arguments, but got ${value.size}")
        }

        for (i in callable.parameters.indices) {
            encodeSerializableElement(descriptor, i, callableSerializers[i], value[i])
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Array<Any?> = decoder.decodeStructure(descriptor) {
        val parameterCount = callable.parameters.size
        val result = arrayOfNulls<Any?>(parameterCount)
        val seen = BooleanArray(parameterCount)
        var remaining = parameterCount

        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) {
                break
            }

            result[index] = decodeSerializableElement(descriptor, index, callableSerializers[index])
            if (!seen[index]) {
                seen[index] = true
                remaining--
            }
        }

        if (remaining != 0) {
            val missing = ObjectArrayList<String>(remaining)

            for (i in 0 until parameterCount) {
                if (!seen[i]) {
                    missing.add(callable.parameters[i].name)
                }
            }

            throw MissingFieldException(missing, callable.name)
        }


        result
    }
}