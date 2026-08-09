package dev.slne.surf.eventbus.audit

import dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService

/**
 * Where audit reports go over the wire.
 *
 * The audit uses its own medicine: a fire-and-forget RPC call. The durable service queue of the
 * microservice carries the report while it restarts or is redeployed — exactly the property the
 * dead-letter queue was there for — and the reporting process does not wait for it.
 */
@RpcService(service = "surf-eventbus-audit")
interface AuditService {
    @FireAndForget
    suspend fun report(report: AuditReport)
}
