package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryFrame
import dev.slne.surf.eventbus.transport.QueryTransport
import java.util.concurrent.CopyOnWriteArrayList

class FakeEventTransport : EventTransport {

    var exactTopics: Set<String> = emptySet()
        private set
    var wildcardPatterns: Set<String> = emptySet()
        private set
    var disconnected = false
        private set

    val published = CopyOnWriteArrayList<Pair<EventEnvelope, ByteArray?>>()

    private var onEvent: (suspend (EventEnvelope, ByteArray?) -> Unit)? = null

    override suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        this.exactTopics = exactTopics
        this.wildcardPatterns = wildcardPatterns
        this.onEvent = onEvent
    }

    override suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        published += envelope to binaryPayload
    }

    override suspend fun disconnect() {
        disconnected = true
    }

    /** Feeds an envelope back in, as if the broker had delivered it. */
    suspend fun deliver(envelope: EventEnvelope, binaryPayload: ByteArray? = null) {
        onEvent?.invoke(envelope, binaryPayload)
    }
}

class FakeQueryTransport : QueryTransport {

    var contracts: Set<String> = emptySet()
        private set
    val asked = CopyOnWriteArrayList<QueryFrame>()
    val answered = CopyOnWriteArrayList<Pair<QueryFrame, String>>()
    var nextAnswer: String? = null

    override suspend fun connect(contracts: Set<String>, onQuery: suspend (QueryFrame) -> Unit) {
        this.contracts = contracts
    }

    override suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String? {
        asked += frame
        return nextAnswer
    }

    override suspend fun answer(frame: QueryFrame, payload: String) {
        answered += frame to payload
    }

    override suspend fun disconnect() = Unit
}
