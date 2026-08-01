package dev.slne.surf.eventbus.rabbitmq.audit

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditService
import dev.slne.surf.eventbus.audit.AuditSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Reports audit incidents over the audit service's own `@FireAndForget` RPC call.
 *
 * The queue and the drain loop run independently of the caller: [report] never suspends on the
 * network, so a slow or unreachable audit service cannot slow down the path that is reporting the
 * incident in the first place.
 */
class RabbitAuditSink(
    private val proxy: AuditService,
    private val serviceName: String,
    private val auditServiceName: String,
    private val enabled: Boolean = true,
    private val queueCapacity: Int = 256,
) : AuditSink {

    companion object {
        private val log = logger()
        private val DROP_LOG_INTERVAL_NANOS = 30.seconds.inWholeNanoseconds
    }

    private val pending = Channel<AuditReport>(capacity = queueCapacity)
    private val dropped = AtomicInteger(0)
    private val lastDropLogNanos = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            for (next in pending) {
                // Rule 3: a failure on the reporting path stays here. The original path -
                // acking the message, logging the handler failure - must not fail because of it.
                try {
                    proxy.report(next)
                } catch (throwable: Throwable) {
                    log.atWarning().withCause(throwable)
                        .log("Could not report audit incident %s (%s)", next.messageUuid, next.kind)
                }
            }
        }
    }

    override suspend fun report(report: AuditReport) {
        if (!enabled) return

        // Rule 2: a process that *is* the audit service logs locally instead of sending.
        // Without this its own failures would produce reports that produce failures.
        if (serviceName == auditServiceName) {
            log.atWarning().log(
                "Audit (%s) in the audit service itself: %s", report.kind, report.exceptionMessage
            )
            return
        }

        // Flood control: an outage that fails ten thousand messages must not become an outage
        // that fills the broker with ten thousand reports.
        if (!pending.trySend(report).isSuccess) {
            dropped.incrementAndGet()
            logDroppedPeriodically()
        }
    }

    /** Reports dropped so far due to a full queue. */
    fun droppedCount(): Int = dropped.get()

    private fun logDroppedPeriodically() {
        val now = System.nanoTime()
        val last = lastDropLogNanos.get()

        if (now - last < DROP_LOG_INTERVAL_NANOS) return
        if (!lastDropLogNanos.compareAndSet(last, now)) return

        log.atWarning().log("Dropped %s audit reports so far because the report queue is full", dropped.get())
    }
}
