package dev.slne.surf.eventbus.rabbitmq.exception.protocol

class SurfRabbitProtocolMissingChunkException : SurfRabbitProtocolException("Missing chunk while assembling packet")
