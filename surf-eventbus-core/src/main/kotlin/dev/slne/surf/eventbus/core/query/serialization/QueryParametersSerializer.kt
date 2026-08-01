package dev.slne.surf.eventbus.core.query.serialization

import dev.slne.surf.eventbus.query.callable.QueryCallable
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
import kotlinx.serialization.serializer

/** Encodes/decodes the argument array of one `@QueryService` callable as a single JSON object. */
class QueryParametersSerializer(
    private val callable: QueryCallable<*>,
    module: SerializersModule
) : KSerializer<Array<Any?>> {
    private val callableSerializers = Array(callable.parameters.size) { i ->
        module.serializer(callable.parameters[i].type)
    }

    override val descriptor = buildClassSerialDescriptor("surf.eventbus.query.QueryParametersSerializer") {
        for ((index, serializer) in callableSerializers.withIndex()) {
            element(callable.parameters[index].name, serializer.descriptor)
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
            if (index == CompositeDecoder.DECODE_DONE) break

            result[index] = decodeSerializableElement(descriptor, index, callableSerializers[index])
            seen[index] = true
        }

        for (i in callable.parameters.indices) {
            if (!seen[i]) throw MissingFieldException(callable.parameters[i].name, callable.name)
        }

        result
    }
}
