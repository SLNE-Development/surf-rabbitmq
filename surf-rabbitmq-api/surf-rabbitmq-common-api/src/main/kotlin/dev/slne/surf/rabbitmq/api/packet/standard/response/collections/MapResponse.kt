package dev.slne.surf.rabbitmq.api.packet.standard.response.collections

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
abstract class MapResponse<K : Any, V : Any, out M : Map<K, V>>(
    private val mapInitializer: () -> M,

    @Transient
    open val value: M = mapInitializer()
) : RabbitResponsePacket() {
    @Serializable
    open class MapResponsePacket<K : Any, V : Any>(
        override val value: Map<K, V>
    ) : MapResponse<K, V, Map<K, V>>({ emptyMap() }, value)

    @Serializable
    open class MutableMapResponsePacket<K : Any, V : Any>(
        override val value: MutableMap<K, V>
    ) : MapResponse<K, V, MutableMap<K, V>>({ mutableMapOf() }, value)
}