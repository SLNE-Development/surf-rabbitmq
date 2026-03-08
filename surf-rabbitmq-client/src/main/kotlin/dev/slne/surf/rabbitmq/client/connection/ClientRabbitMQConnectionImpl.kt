package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.expireAfterWrite
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.connection.AbstractRabbitMQConnectionImpl
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketSerializer
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class ClientRabbitMQConnectionImpl(private val api: RabbitMQApi, config: RabbitMQConfig) :
    AbstractRabbitMQConnectionImpl(api, config), ClientRabbitMQConnection {

    private val pendingRequests = Caffeine.newBuilder()
        .expireAfterWrite(15.minutes)
        .evictionListener<String, CompletableDeferred<ByteArray>> { _, deferred, _ ->
            if (deferred != null && !deferred.isCompleted) {
                deferred.completeExceptionally(IllegalStateException("Request timed out"))
            }
        }
        .build<String, CompletableDeferred<ByteArray>>()

    private val serializerCache = KotlinSerializerCache.Companion<Any>(api.cbor.serializersModule)
    private val responseSerializerCache = KotlinSerializerNameCache<RabbitResponsePacket>(api.cbor.serializersModule)

    private lateinit var callbackQueueName: String

    override suspend fun connect() {
        super.connect()

        callbackQueueName = channel.queueDeclare {
            name = queueName + "_callback"
            durable = false
            exclusive = true
            autoDelete = true
        }.queueName

        startConsumingResponses()
    }

    private suspend fun startConsumingResponses() {
        val consume = channel.basicConsume(callbackQueueName)

        api.scope.launch {
            for (message in consume) {
                val correlationId = message.message.properties.correlationId ?: continue
                pendingRequests.asMap().remove(correlationId)?.complete(message.message.body)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R {
        val serializer = serializerCache.get(request.javaClass) as? KSerializer<RabbitRequestPacket<*>>
            ?: error("No serializer found for class ${request.javaClass.name}")
        responseSerializerCache.register(responseClass)

        val requestBytes = RabbitPacketSerializer.serializeRequest(api, serializer, request)
        val responseBytes = awaitResponse(requestBytes)
        val response = RabbitPacketSerializer.deserializeResponse(api, responseBytes, responseSerializerCache)

        return response as R
    }


    private suspend fun awaitResponse(body: ByteArray): ByteArray {
        val correlationId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ByteArray>()
        pendingRequests.put(correlationId, deferred)

        channel.basicPublish {
            this.body = body
            exchange = ""
            routingKey = queueName
            properties = properties {
                deliveryMode = 2u // Persistent message by default; only works with durable queues
                this.correlationId = correlationId
                this.replyTo = callbackQueueName
            }
        }

        return try {
            withTimeout(1.minutes) { // TODO: make timeout configurable
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            val completable = pendingRequests.asMap().remove(correlationId)
            completable?.completeExceptionally(e)

            error("Request timed out after 1 minute")
        }
    }

    override suspend fun disconnect() {
        super.disconnect()
    }
}