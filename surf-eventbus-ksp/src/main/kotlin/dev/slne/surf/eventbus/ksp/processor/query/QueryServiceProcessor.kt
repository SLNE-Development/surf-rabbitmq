package dev.slne.surf.eventbus.ksp.processor.query

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import dev.slne.surf.eventbus.ksp.processor.query.codegen.QueryDescriptorCodegen
import dev.slne.surf.eventbus.ksp.processor.query.model.QueryServiceModelFactory

class QueryServiceProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private val codeGenerator = environment.codeGenerator

    private val modelFactory = QueryServiceModelFactory(logger)
    private val descriptorCodegen = QueryDescriptorCodegen(codeGenerator)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        resolver.getSymbolsWithAnnotation(Names.QUERY_SERVICE_ANNOTATION_FQ)
            .forEach(fun(declaration) {
                if (declaration !is KSClassDeclaration) {
                    logger.error(
                        "${Names.QUERY_SERVICE_ANNOTATION} is only applicable to interfaces, but was found on $declaration",
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
            })

        return deferred
    }
}
