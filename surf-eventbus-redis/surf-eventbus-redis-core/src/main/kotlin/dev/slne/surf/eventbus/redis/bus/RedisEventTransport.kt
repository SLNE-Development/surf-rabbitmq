package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.event.EventTopics
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.util.InternalRedisAPI
import dev.slne.surf.eventbus.transport.EventTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.serialization.json.Json
import org.redisson.client.codec.ByteArrayCodec
import org.redisson.client.codec.StringCodec
import reactor.core.Disposable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries events over Redis Pub/Sub.
 *
 * Wildcard-free subscriptions take an exact channel and let the broker filter. Wildcard
 * subscriptions take a pattern subscription over both families and match locally with
 * `EventTopics`, because Redis glob and the documented topic semantics disagree and the
 * documented one wins.
 */
@OptIn(InternalRedisAPI::class)
class RedisEventTransport(
    private val redis: RedisApi,
    private val json: Json,
    private val ensureConnected: suspend () -> Unit = {}
) : EventTransport {

    private val disposables = CopyOnWriteArrayList<Disposable>()

    override suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        ensureConnected()

        for (topic in exactTopics) {
            subscribeJson(RedisChannels.json(topic), onEvent)
            subscribeBinary(RedisChannels.binary(topic), onEvent)
        }

        if (wildcardPatterns.isNotEmpty()) {
            subscribeJsonPattern(wildcardPatterns, onEvent)
            subscribeBinaryPattern(wildcardPatterns, onEvent)
        }
    }

    override suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        ensureConnected()

        if (binaryPayload == null) {
            redis.redissonReactive
                .getTopic(RedisChannels.json(envelope.topic), StringCodec.INSTANCE)
                .publish(envelope.encodeToString(json))
                .awaitFirstOrNull()
        } else {
            redis.redissonReactive
                .getTopic(RedisChannels.binary(envelope.topic), ByteArrayCodec.INSTANCE)
                .publish(BinaryFrame.encode(envelope, binaryPayload, json))
                .awaitFirstOrNull()
        }
    }

    override suspend fun disconnect() {
        disposables.forEach { runCatching { it.dispose() } }
        disposables.clear()
    }

    private fun subscribeJson(channel: String, onEvent: suspend (EventEnvelope, ByteArray?) -> Unit) {
        val topic = redis.redissonReactive.getTopic(channel, StringCodec.INSTANCE)
        val disposable = topic.addListener(String::class.java) { _, message ->
            deliverJson(message, onEvent)
        }.subscribe()
        disposables += disposable
    }

    private fun subscribeBinary(channel: String, onEvent: suspend (EventEnvelope, ByteArray?) -> Unit) {
        val topic = redis.redissonReactive.getTopic(channel, ByteArrayCodec.INSTANCE)
        val disposable = topic.addListener(ByteArray::class.java) { _, message ->
            deliverBinary(message, onEvent)
        }.subscribe()
        disposables += disposable
    }

    private fun subscribeJsonPattern(
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        val pattern = redis.redissonReactive.getPatternTopic(RedisChannels.JSON_PATTERN, StringCodec.INSTANCE)
        val disposable = pattern.addListener(String::class.java) { _, channel, message ->
            if (matchesWildcards(channel.toString(), wildcardPatterns)) deliverJson(message, onEvent)
        }.subscribe()
        disposables += disposable
    }

    private fun subscribeBinaryPattern(
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        val pattern = redis.redissonReactive.getPatternTopic(RedisChannels.BINARY_PATTERN, ByteArrayCodec.INSTANCE)
        val disposable = pattern.addListener(ByteArray::class.java) { _, channel, message ->
            if (matchesWildcards(channel.toString(), wildcardPatterns)) deliverBinary(message, onEvent)
        }.subscribe()
        disposables += disposable
    }

    private fun matchesWildcards(channel: String, wildcardPatterns: Set<String>): Boolean {
        val topic = RedisChannels.topicOf(channel) ?: return false
        return wildcardPatterns.any { pattern -> EventTopics.matches(pattern, topic) }
    }

    private fun deliverJson(message: String, onEvent: suspend (EventEnvelope, ByteArray?) -> Unit) {
        redis.redisListenerScope.launch {
            onEvent(EventEnvelope.decodeFromString(json, message), null)
        }
    }

    private fun deliverBinary(message: ByteArray, onEvent: suspend (EventEnvelope, ByteArray?) -> Unit) {
        redis.redisListenerScope.launch {
            val (envelope, payload) = BinaryFrame.decode(message, json)
            onEvent(envelope, payload)
        }
    }

    companion object {
        private val log = logger()
    }
}
