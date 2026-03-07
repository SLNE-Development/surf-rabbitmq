@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.wrapper

import dev.kourier.amqp.properties
import dev.slne.surf.rabbitmq.wrapper.packet.RabbitPacket
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import java.util.*
import kotlin.reflect.KClass

class RabbitMQService(
    serviceName: String,
    val api: RabbitMQApi
) {
    private val exchangeName = serviceName
    private val queueName = serviceName
    private val callbackQueueName = "${serviceName}_callback"

    suspend fun declare() {
        api.declareExchange(exchangeName)
        api.declareCallbackQueue(queueName, callbackQueueName)
    }

    suspend fun <Response : RabbitPacket> publish(
        packetClass: KClass<RabbitPacket>,
        packet: RabbitPacket,
    ): Response {
        val consumer = api.channel.basicConsume(callbackQueueName, noAck = true)
        var response: Response? = null

        val correlationId = UUID.randomUUID().toString()
        val requestProperties = properties {
            this.replyTo = callbackQueueName
            this.correlationId = correlationId
        }

        api.publish(
            messageClass = packetClass,
            message = packet,
            queueName = queueName,
            exchange = exchangeName,
            properties = requestProperties,
        )

        for (delivery in consumer) {
            val message = delivery.message
            val responseCorrelationId = message.properties.correlationId

            if (responseCorrelationId == correlationId) {
                response = RabbitMQSerializer.CBOR.decodeFromByteArray(
                    RabbitMQSerializer.serializerModule.serializer<RabbitPacket>(),
                    message.body
                )
            }
        }

        return response
            ?: throw IllegalStateException("No response received for correlationId: $correlationId")
    }
}