package dev.slne.surf.rabbitmq.processor.rpc

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import dev.slne.surf.rabbitmq.processor.Names
import dev.slne.surf.rabbitmq.processor.rpc.codegen.RpcClientImplCodegen
import dev.slne.surf.rabbitmq.processor.rpc.codegen.RpcDescriptorCodegen
import dev.slne.surf.rabbitmq.processor.rpc.model.RpcServiceModelFactory

class RpcServiceProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private val codeGenerator = environment.codeGenerator

    private val modelFactory = RpcServiceModelFactory(logger)
    private val descriptorCodegen = RpcDescriptorCodegen(codeGenerator)
    private val clientImplCodegen = RpcClientImplCodegen(logger, codeGenerator)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        resolver.getSymbolsWithAnnotation(Names.RPC_SERVICE_ANNOTATION_FQ)
            .forEach(fun(declaration) {
                if (declaration !is KSClassDeclaration) {
                    logger.error(
                        "${Names.RPC_SERVICE_ANNOTATION} is only applicable to interfaces, but was found on $declaration",
                        declaration
                    )
                    return
                }

                if (!declaration.validate()) {
                    deferred += declaration
                    return
                }

                val model = modelFactory.create(declaration) ?: return
                descriptorCodegen.generate(model)
                clientImplCodegen.generate(model)
            })

        return deferred
    }
}