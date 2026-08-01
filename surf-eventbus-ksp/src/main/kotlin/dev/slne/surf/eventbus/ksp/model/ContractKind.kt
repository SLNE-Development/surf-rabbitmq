package dev.slne.surf.eventbus.ksp.model

/**
 * Which promise a contract makes.
 *
 * The two differ in validation and in what is generated, not in how they are read — which is
 * why one factory serves both and takes this as a parameter.
 */
enum class ContractKind(val annotationFqName: String, val annotationSimpleName: String) {
    QUERY("dev.slne.surf.eventbus.query.QueryService", "QueryService"),
    RPC("dev.slne.surf.eventbus.rabbitmq.rpc.RpcService", "RpcService")
}
