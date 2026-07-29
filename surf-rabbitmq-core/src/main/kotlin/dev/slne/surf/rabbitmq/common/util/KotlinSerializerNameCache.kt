package dev.slne.surf.rabbitmq.common.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull
import java.util.concurrent.ConcurrentHashMap

class KotlinSerializerNameCache<T>(private val module: SerializersModule) {
    private val cache = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun get(className: String): KSerializer<T>? {
        val cached = cache[className] ?: return null
        if (cached === NULL_MARKER) return null
        return cached as? KSerializer<T>
    }

    fun register(clazz: Class<out T>) {
        cache.computeIfAbsent(clazz.name) {
            module.serializerOrNull(clazz) ?: NULL_MARKER
        }
    }

    companion object {
        private val NULL_MARKER = Any()
    }
}