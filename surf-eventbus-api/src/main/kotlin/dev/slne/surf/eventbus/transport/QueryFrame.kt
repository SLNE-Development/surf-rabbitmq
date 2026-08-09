package dev.slne.surf.eventbus.transport

import kotlinx.serialization.Serializable

/**
 * One question or answer on the wire.
 */
@Serializable
data class QueryFrame(
    val contract: String,
    val callable: String,
    val correlationId: String,
    val originInstanceId: String,
    val payload: String
)
