package dev.slne.surf.rabbitmq.listener;

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket;

@FunctionalInterface
public interface RabbitListenerHandler {

    void handle(RabbitRequestPacket<?> message);
}
