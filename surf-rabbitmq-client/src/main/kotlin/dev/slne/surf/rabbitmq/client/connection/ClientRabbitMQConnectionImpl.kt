package dev.slne.surf.rabbitmq.client.connection

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.expireAfterWrite
import dev.kourier.amqp.channel.basicPublish
import dev.kourier.amqp.channel.queueDeclare
import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
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
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ClientRabbitMQConnectionImpl(private val api: RabbitMQApi, config: RabbitMQConfig) :
    AbstractRabbitMQConnectionImpl(api, config), ClientRabbitMQConnection {

    private val requestTimeoutSeconds = config.requestTimeoutSeconds.seconds
    private val pendingRequests = Caffeine.newBuilder()
        .expireAfterWrite(requestTimeoutSeconds * 2)
        .evictionListener<String, CompletableDeferred<ByteArray>> { _, deferred, _ ->
            if (deferred != null && !deferred.isCompleted) {
                deferred.completeExceptionally(SurfRabbitRequestTimeoutException(requestTimeoutSeconds))
            }
        }
        .build<String, CompletableDeferred<ByteArray>>()

    private val requestSerializerCache = KotlinSerializerCache<RabbitRequestPacket<*>>(api.cbor.serializersModule)
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
        val serializer = requestSerializerCache.get(request.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(request.javaClass.name)
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
            withTimeout(requestTimeoutSeconds) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.invalidate(correlationId)
            throw SurfRabbitRequestTimeoutException(requestTimeoutSeconds)
        }
    }

    override suspend fun disconnect() {
        super.disconnect()
    }
}