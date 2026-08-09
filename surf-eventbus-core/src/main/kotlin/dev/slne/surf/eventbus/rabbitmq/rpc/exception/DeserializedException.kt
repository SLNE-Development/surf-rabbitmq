package dev.slne.surf.eventbus.rabbitmq.rpc.exception

/**
 * Fallback for remote exceptions whose original class could not be reconstructed.
 * Preserves the original `toString()`, message, stack trace and cause chain.
 */
class DeserializedException(
    private val toStringMessage: String,
    override val message: String?,
    stacktrace: List<StackElement>,
    cause: SerializedException?,
    val className: String
) : Throwable() {
    override val cause: Throwable? = cause?.deserialize()

    init {
        stackTrace = stacktrace.map { it.toStackTraceElement() }.toTypedArray()
    }

    override fun toString(): String = toStringMessage
}
