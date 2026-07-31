package dev.slne.surf.eventbus.rabbitmq.shared.dispatch;

import java.lang.invoke.MethodHandles;

public final class HandlerMethodHandleProvider {
    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private HandlerMethodHandleProvider() {
        throw new AssertionError("No instances");
    }
}
