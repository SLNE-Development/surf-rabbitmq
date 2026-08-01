package dev.slne.surf.eventbus.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import dev.slne.surf.eventbus.ksp.codegen.QueryDescriptorCodegen
import dev.slne.surf.eventbus.ksp.codegen.RpcClientCodegen
import dev.slne.surf.eventbus.ksp.codegen.RpcDescriptorCodegen
import dev.slne.surf.eventbus.ksp.model.ContractKind
import dev.slne.surf.eventbus.ksp.model.ServiceModelFactory

/**
 * Reads `@QueryService` and `@RpcService` in one pass.
 *
 * Two providers meant two resolver walks over the same file, two `Names` objects, two model
 * factories and two descriptor codegens — and a consumer's single `ksp(...)` line silently
 * attaching both.
 */
class ServiceProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private val modelFactory = ServiceModelFactory(logger)
    private val queryCodegen = QueryDescriptorCodegen(environment.codeGenerator)
    private val rpcCodegen = RpcDescriptorCodegen(environment.codeGenerator)
    private val rpcClientCodegen = RpcClientCodegen(logger, environment.codeGenerator)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (kind in ContractKind.entries) {
            resolver.getSymbolsWithAnnotation(kind.annotationFqName).forEach { declaration ->
                if (declaration !is KSClassDeclaration) {
                    logger.error(
                        "${kind.annotationSimpleName} is only applicable to interfaces, " +
                                "but was found on $declaration",
                        declaration
                    )
                    return@forEach
                }

                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }

                val model = modelFactory.create(declaration, kind) ?: return@forEach

                when (kind) {
                    ContractKind.QUERY -> queryCodegen.generate(model)
                    ContractKind.RPC -> {
                        rpcCodegen.generate(model)
                        rpcClientCodegen.generate(model)
                    }
                }
            }
        }

        return deferred
    }
}
