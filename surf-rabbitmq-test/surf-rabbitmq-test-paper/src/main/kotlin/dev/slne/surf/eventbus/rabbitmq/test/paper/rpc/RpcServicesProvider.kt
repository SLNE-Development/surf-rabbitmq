package dev.slne.surf.eventbus.rabbitmq.test.paper.rpc

import dev.slne.surf.eventbus.rabbitmq.test.paper.rabbitMqApi
import dev.slne.surf.eventbus.rabbitmq.test.rpc.RabbitMqTestRpcService

val rabbitMqTestService by lazy { rabbitMqApi.rpc<RabbitMqTestRpcService>() }