package dev.slne.surf.rabbitmq.api.packet

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates

@Serializable
abstract class RabbitRequestPacket<ResponsePacket : RabbitResponsePacket> : RabbitPacket(), CoroutineScope {

    final override var coroutineContext: CoroutineContext by Delegates.notNull()
        @InternalRabbitMQ set

    @InternalRabbitMQ
    val responseDeferred by lazy { CompletableDeferred<ResponsePacket>() }

    fun respond(response: ResponsePacket) {
        require(responseDeferred.isCompleted.not()) { "Response already sent" }
        responseDeferred.complete(response)
    }
}