package dev.slne.surf.eventbus.redis.cache

import dev.slne.surf.eventbus.InternalEventBusApi

class RedisSetIndex<T : Any, V : Any> internal constructor(
    val name: String,
    private val valuesOf: (T) -> Iterable<V>,
    private val valueToString: (V) -> String,
    private val normalize: (String) -> String
) {
    @InternalEventBusApi
    fun extractStringsSequence(element: T): Sequence<String> = valuesOf(element).asSequence()
        .map(valueToString)
        .map(normalize)
        .filter { it.isNotEmpty() }

    @InternalEventBusApi
    fun extractStrings(element: T): Set<String> = extractStringsSequence(element).toSet()

    @InternalEventBusApi
    fun valueString(value: V): String =
        normalize(valueToString(value)).also {
            require(it.isNotEmpty()) { "Index '$name' produced blank key for value '$value'" }
        }

    override fun toString(): String = "RedisSetIndex(name='$name')"
}
