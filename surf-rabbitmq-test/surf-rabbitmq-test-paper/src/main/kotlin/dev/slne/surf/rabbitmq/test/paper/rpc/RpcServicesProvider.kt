package dev.slne.surf.rabbitmq.test.paper.rpc

import dev.slne.surf.rabbitmq.test.paper.rabbitMqApi
import dev.slne.surf.rabbitmq.test.rpc.RabbitMqTestRpcService

val rabbitMqTestService by lazy { rabbitMqApi.createRpcService<RabbitMqTestRpcService>() }