package dev.slne.surf.eventbus.transport

/**
 * One question or answer on the wire.
 */
data class QueryFrame(
    val contract: String,
    val callable: String,
    val correlationId: String,
    val originInstanceId: String,
    val payload: String
)

/**
 * The seam between the bus and the Redis query channels.
 *
 * One real implementation, same reasoning as [EventTransport].
 */
interface QueryTransport {

    suspend fun connect(contracts: Set<String>, onQuery: suspend (QueryFrame) -> Unit)

    /**
     * Publishes the question and waits for the first answer.
     *
     * @return the answer payload, or `null` when nobody answered within [timeoutMillis]. No
     *   exception: in a broadcast, "nobody is responsible" is a normal outcome.
     */
    suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String?

    /** Sends an answer to the asker named in [frame]. Called only when the handler abstained not. */
    suspend fun answer(frame: QueryFrame, payload: String)

    suspend fun disconnect()
}
