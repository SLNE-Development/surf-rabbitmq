package dev.slne.surf.rabbitmq.api.packet.standard.response.collections

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
abstract class CollectionResponse<L : Any, C : Collection<L>>(
    private val emptyCollection: () -> C,

    @Transient
    open val value: C = emptyCollection()
) : RabbitResponsePacket(), Iterable<L> {
    override fun iterator(): Iterator<L> = value.iterator()

    @Serializable
    open class ListResponsePacket<L : Any>(
        override val value: List<L>
    ) : CollectionResponse<L, List<L>>({ listOf() }, value)

    @Serializable
    open class MutableListResponsePacket<L : Any>(
        override val value: MutableList<L>
    ) : CollectionResponse<L, MutableList<L>>({ mutableListOf() }, value)
}