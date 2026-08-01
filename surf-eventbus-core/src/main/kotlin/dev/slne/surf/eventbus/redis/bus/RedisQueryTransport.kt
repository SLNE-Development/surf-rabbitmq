package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.redis.util.InternalRedisAPI
import dev.slne.surf.eventbus.transport.QueryFrame
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.redisson.api.RTopicReactive
import org.redisson.client.codec.StringCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries queries and their answers over Redis Pub/Sub.
 *
 * One channel per contract (`surf.eventbus.query.<contract>`), one reply channel per instance
 * (`surf.eventbus.reply.<instanceId>`). [ask] parks a [CompletableDeferred] under the frame's
 * `correlationId` and lets the first reply on the instance's own reply channel complete it;
 * every later reply for the same id is simply not waited for anymore - "first answer wins".
 */
@OptIn(InternalRedisAPI::class)
class RedisQueryTransport(
    private val redis: RedisApi,
    private val json: Json,
    private val ensureConnected: suspend () -> Unit = {}
) : QueryTransport {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val subscriptions = CopyOnWriteArrayList<Pair<RTopicReactive, Int>>()

    override suspend fun connect(contracts: Set<String>, instanceId: String, onQuery: suspend (QueryFrame) -> Unit) {
        ensureConnected()

        for (contract in contracts) {
            subscribeQuery(RedisChannels.query(contract), onQuery)
        }
        subscribeReply(RedisChannels.reply(instanceId))
    }

    override suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String? {
        ensureConnected()

        val deferred = CompletableDeferred<String>()
        pending[frame.correlationId] = deferred

        try {
            redis.redissonReactive
                .getTopic(RedisChannels.query(frame.contract), StringCodec.INSTANCE)
                .publish(json.encodeToString(QueryFrame.serializer(), frame))
                .awaitFirstOrNull()

            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(frame.correlationId)
        }
    }

    override suspend fun answer(frame: QueryFrame, payload: String) {
        ensureConnected()

        val answerFrame = frame.copy(payload = payload)

        redis.redissonReactive
            .getTopic(RedisChannels.reply(frame.originInstanceId), StringCodec.INSTANCE)
            .publish(json.encodeToString(QueryFrame.serializer(), answerFrame))
            .awaitFirstOrNull()
    }

    override suspend fun disconnect() {
        subscriptions.forEach { (topic, id) -> runCatching { topic.removeListener(id) } }
        subscriptions.clear()
        pending.clear()
    }

    // Awaits the subscription itself (not just firing off the request for one): a query or
    // reply published right after connect() returns must not race an in-flight SUBSCRIBE, or
    // the message is gone for good - Pub/Sub has no redelivery.
    private suspend fun subscribeQuery(channel: String, onQuery: suspend (QueryFrame) -> Unit) {
        val topic = redis.redissonReactive.getTopic(channel, StringCodec.INSTANCE)
        val listenerId = topic.addListener(String::class.java) { _, message ->
            redis.redisListenerScope.launch {
                onQuery(json.decodeFromString(QueryFrame.serializer(), message))
            }
        }.awaitSingle()
        subscriptions += topic to listenerId
    }

    private suspend fun subscribeReply(channel: String) {
        val topic = redis.redissonReactive.getTopic(channel, StringCodec.INSTANCE)
        val listenerId = topic.addListener(String::class.java) { _, message ->
            val frame = json.decodeFromString(QueryFrame.serializer(), message)
            pending[frame.correlationId]?.complete(frame.payload)
        }.awaitSingle()
        subscriptions += topic to listenerId
    }
}
