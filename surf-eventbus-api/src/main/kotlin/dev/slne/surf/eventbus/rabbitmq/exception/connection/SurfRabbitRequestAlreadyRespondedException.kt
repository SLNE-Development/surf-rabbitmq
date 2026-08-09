package dev.slne.surf.eventbus.rabbitmq.exception.connection

class SurfRabbitRequestAlreadyRespondedException : SurfRabbitRequestException("respond() has already been called for this request")
