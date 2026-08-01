package dev.slne.surf.eventbus.service.serialization

import dev.slne.surf.eventbus.service.ServiceCallable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.modules.SerializersModule

/**
 * Encodes the argument array of one contract callable as a single object.
 *
 * One class for queries and for RPC. They had two, differing in whether an optional parameter
 * may be omitted and in whether contextual serializers are consulted — differences that were
 * accidents of which sub-project wrote them, not of what the transports need.
 */
class ParametersSerializer(
    private val callable: ServiceCallable<*>,
    private val module: SerializersModule
) : KSerializer<Array<Any?>> {
    private val callableSerializers = Array(callable.parameters.size) { i ->
        module.buildContextual(callable.parameters[i].type)
    }

    override val descriptor = buildClassSerialDescriptor("surf.eventbus.ParametersSerializer") {
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
            if (!seen[i] && !parameter.isOptional) {
                throw MissingFieldException(parameter.name, callable.name)
            }
        }

        result
    }
}
