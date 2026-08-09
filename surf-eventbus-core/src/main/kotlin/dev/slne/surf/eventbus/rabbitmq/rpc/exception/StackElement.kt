package dev.slne.surf.eventbus.rabbitmq.rpc.exception

import kotlinx.serialization.Serializable

@Serializable
data class StackElement(
    val clazz: String,
    val method: String,
    val fileName: String?,
    val lineNumber: Int
) {
    fun toStackTraceElement(): StackTraceElement =
        StackTraceElement(clazz, method, fileName, lineNumber)
}
