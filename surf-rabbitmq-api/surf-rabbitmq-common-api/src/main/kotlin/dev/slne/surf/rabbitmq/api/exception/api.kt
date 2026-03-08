package dev.slne.surf.rabbitmq.api.exception

class SurfRabbitApiNotFrozenException :
    SurfRabbitException("RabbitMQApi must be frozen before connecting — call freeze() or freezeAndConnect() first")
