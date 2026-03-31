package dev.slne.surf.rabbitmq.listener;

import java.lang.invoke.MethodHandles;

public final class RabbitListenerMethodHandleProvider {
    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private RabbitListenerMethodHandleProvider() {
        throw new AssertionError("No instances");
    }
}
