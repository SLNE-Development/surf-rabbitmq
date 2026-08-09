package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.event.EventTopics
import dev.slne.surf.eventbus.redis.SurfRedisApi
import dev.slne.surf.eventbus.transport.EventEnvelope
import dev.slne.surf.eventbus.transport.EventTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.redisson.api.RTopicReactive
import org.redisson.client.codec.ByteArrayCodec
import org.redisson.client.codec.StringCodec
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries events over Redis Pub/Sub.
 *
 * Wildcard-free subscriptions take an exact channel and let the broker filter. Wildcard
 * subscriptions take a pattern subscription over both families and match locally with
 * `EventTopics`, because Redis glob and the documented topic semantics disagree and the
 * documented one wins.
 */
@OptIn(InternalEventBusApi::class)
class RedisEventTransport(
    private val redis: SurfRedisApi,
    private val json: Json,
    private val ensureConnected: suspend () -> Unit = {},
) : EventTransport {
    private val subscriptions = CopyOnWriteArrayList<ListenerHandle>()

    override suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
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

    override suspend fun publish(
        envelope: EventEnvelope,
        binaryPayload: ByteArray?,
    ) {
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
        subscriptions.forEach { handle -> runCatching { handle.remove() } }
        subscriptions.clear()
    }

    // Every subscribe below awaits the listener id rather than firing off the SUBSCRIBE and
    // moving on. An event published right after connect() returns must not race an in-flight
    // SUBSCRIBE, because Pub/Sub has no redelivery and the event is then gone for good - the
    // same reason RedisQueryTransport.subscribeQuery has always awaited its id.
    //
    // The id is also what makes disconnect() work at all. Disposing the Disposable that
    // .subscribe() returns is a no-op: that subscription has already completed, having
    // delivered the id. Only removeListener(id) detaches the listener.

    private suspend fun subscribeJson(
        channel: String,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        val topic = redis.redissonReactive.getTopic(channel, StringCodec.INSTANCE)
        val listenerId =
            topic
                .addListener(String::class.java) { _, message ->
                    deliverJson(message, onEvent)
                }.awaitSingle()
        subscriptions += ListenerHandle.Topic(topic, listenerId)
    }

    private suspend fun subscribeBinary(
        channel: String,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        val topic = redis.redissonReactive.getTopic(channel, ByteArrayCodec.INSTANCE)
        val listenerId =
            topic
                .addListener(ByteArray::class.java) { _, message ->
                    deliverBinary(message, onEvent)
                }.awaitSingle()
        subscriptions += ListenerHandle.Topic(topic, listenerId)
    }

    private suspend fun subscribeJsonPattern(
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        val pattern =
            redis.redissonReactive.getPatternTopic(RedisChannels.JSON_PATTERN, StringCodec.INSTANCE)
        val listenerId =
            pattern
                .addListener(String::class.java) { _, channel, message ->
                    if (matchesWildcards(channel.toString(), wildcardPatterns)) {
                        deliverJson(
                            message,
                            onEvent,
                        )
                    }
                }.awaitSingle()
        subscriptions += ListenerHandle.Pattern(redis, RedisChannels.JSON_PATTERN, listenerId)
    }

    private suspend fun subscribeBinaryPattern(
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        val pattern =
            redis.redissonReactive.getPatternTopic(
                RedisChannels.BINARY_PATTERN,
                ByteArrayCodec.INSTANCE,
            )
        val listenerId =
            pattern
                .addListener(ByteArray::class.java) { _, channel, message ->
                    if (matchesWildcards(channel.toString(), wildcardPatterns)) {
                        deliverBinary(
                            message,
                            onEvent,
                        )
                    }
                }.awaitSingle()
        subscriptions += ListenerHandle.Pattern(redis, RedisChannels.BINARY_PATTERN, listenerId)
    }

    /**
     * A listener that can actually be detached again.
     *
     * Topic and pattern listeners come from two unrelated Redisson types with no common
     * `removeListener` supertype, so the pair is modelled here rather than as
     * `Pair<Any, Int>` plus a cast at removal time.
     */
    private sealed interface ListenerHandle {
        suspend fun remove()

        /** `removeListener` on a reactive topic is a cold Mono, so the removal is awaited. */
        class Topic(
            private val topic: RTopicReactive,
            private val listenerId: Int,
        ) : ListenerHandle {
            override suspend fun remove() {
                topic.removeListener(listenerId).awaitFirstOrNull()
            }
        }

        /**
         * Pattern listeners are removed through the blocking client, deliberately.
         *
         * `RPatternTopicReactive` declares `removeListener(int)`, but the async method behind
         * it is `removeListenerAsync(Integer...)` — varargs — and Redisson's reactive proxy
         * cannot map the two. Calling it throws `NoSuchMethodException` wrapped in an
         * `UndeclaredThrowableException`, which a `runCatching` in `disconnect()` would hide,
         * leaving the listener attached exactly as before. The blocking facade has a working
         * `removeListener(Integer...)`, and both views share one connection manager, so the
         * id from the reactive `addListener` is valid here.
         */
        class Pattern(
            private val redis: SurfRedisApi,
            private val patternName: String,
            private val listenerId: Int,
        ) : ListenerHandle {
            override suspend fun remove() =
                withContext(Dispatchers.IO) {
                    redis.redisson
                        .getPatternTopic(patternName, StringCodec.INSTANCE)
                        .removeListener(listenerId)
                }
        }
    }

    private fun matchesWildcards(
        channel: String,
        wildcardPatterns: Set<String>,
    ): Boolean {
        val topic = RedisChannels.topicOf(channel) ?: return false
        return wildcardPatterns.any { pattern -> EventTopics.matches(pattern, topic) }
    }

    private fun deliverJson(
        message: String,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        redis.scope.launch {
            onEvent(EventEnvelope.decodeFromString(json, message), null)
        }
    }

    private fun deliverBinary(
        message: ByteArray,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit,
    ) {
        redis.scope.launch {
            val (envelope, payload) = BinaryFrame.decode(message, json)
            onEvent(envelope, payload)
        }
    }
}
