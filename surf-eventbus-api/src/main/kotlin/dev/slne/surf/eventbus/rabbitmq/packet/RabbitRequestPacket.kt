package dev.slne.surf.eventbus.rabbitmq.packet

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates
import dev.slne.surf.eventbus.rabbitmq.exception.connection.SurfRabbitRequestAlreadyRespondedException

@Serializable
abstract class RabbitRequestPacket<ResponsePacket : RabbitResponsePacket> : RabbitPacket(),
    CoroutineScope {
    final override var coroutineContext: CoroutineContext by Delegates.notNull()
        @InternalEventBusApi set

    @InternalEventBusApi
    @Transient
    val responseDeferred = CompletableDeferred<ResponsePacket>()

    fun respond(response: ResponsePacket) {
        if (!responseDeferred.complete(response)) {
            throw SurfRabbitRequestAlreadyRespondedException()
        }
    }

    fun hasResponded(): Boolean = responseDeferred.isCompleted
}