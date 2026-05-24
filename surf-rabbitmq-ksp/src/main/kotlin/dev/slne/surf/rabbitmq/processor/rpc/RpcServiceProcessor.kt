package dev.slne.surf.rabbitmq.processor.rpc

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.*
import dev.slne.surf.rabbitmq.processor.*
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
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }

                val model = modelFactory.create(declaration) ?: return@forEach
                descriptorCodegen.generate(model)
                clientImplCodegen.generate(model)
            }

        return deferred


//        val deferred = mutableListOf<KSAnnotated>()
//
//        resolver.getSymbolsWithAnnotation(Names.RPC_SERVICE_ANNOTATION_FQ)
//            .filterIsInstance<KSClassDeclaration>()
//            .forEach { declaration ->
//                if (!declaration.validate()) {
//                    deferred.add(declaration)
//                    return@forEach
//                }
//
//                processClass(declaration)
//            }
//
//        return deferred
    }

    private fun processClass(declaration: KSClassDeclaration) {
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error("Only interfaces can be annotated with @${Names.RPC_SERVICE_ANNOTATION}", declaration)
            return
        }

        val simpleName = declaration.simpleName.asString()
        val fqName = declaration.qualifiedName?.asString() ?: run {
            logger.error("Cannot generate descriptor for interface $simpleName: no qualified name", declaration)
            return
        }
        val packageName = fqName.substringBeforeLast('.', "")

        generateDescriptor(simpleName, fqName, packageName, declaration)
    }

    private fun generateDescriptor(
        simpleName: String,
        fqName: String,
        packageName: String,
        declaration: KSClassDeclaration
    ) {
        val descriptorClassName = "${simpleName}Descriptor"
        val serviceInterfaceClassName = declaration.toClassName()

        FileSpec.Companion.builder(packageName, descriptorClassName).apply {
            addType(TypeSpec.Companion.objectBuilder(descriptorClassName).apply {
                addSuperinterface(ClassNames.rpcServiceDescriptor.parameterizedBy(serviceInterfaceClassName))

                addProperty(
                    PropertySpec.Companion.builder("simpleName", String::class)
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer("%S", simpleName)
                        .build()
                )

                addProperty(
                    PropertySpec.Companion.builder("fqName", String::class)
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer("%S", fqName)
                        .build()
                )

                generateInvokers(declaration, serviceInterfaceClassName)
                generateCallablesMap(declaration, serviceInterfaceClassName)

                addFunction(
                    FunSpec.Companion.builder("getCallable")
                        .addParameter("name", String::class)
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(
                            ClassNames.rpcCallable
                                .parameterizedBy(serviceInterfaceClassName)
                                .copy(nullable = true)
                        )
                        .addStatement("return callables[name]")
                        .build()
                )

                val clientImplClassName =
                    serviceInterfaceClassName.peerClass(serviceInterfaceClassName.simpleName + "ClientImpl")

                addFunction(
                    FunSpec.Companion.builder("createInstance")
                        .returns(serviceInterfaceClassName)
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("serviceId", Long::class)
                        .addParameter("api", ClassNames.rabbitMqApi)
                        .addStatement("return %T(serviceId, api)", clientImplClassName)
                        .build()
                )

            }.build())
        }.build().writeTo(codeGenerator, true)
    }

    private fun TypeSpec.Builder.generateInvokers(
        serviceInterface: KSClassDeclaration,
        serviceInterfaceClassName: ClassName
    ) {
        val generatedFunctionNames = mutableSetOf<String>()
        val classTypeParameterResolver = serviceInterface.typeParameters.toTypeParameterResolver()

        serviceInterface.getAllFunctions().forEach { function ->
            if (isObjectMethod(function)) return@forEach

            val functionName = function.simpleName.asString()

            if (!generatedFunctionNames.add(functionName)) {
                logger.error(
                    "A function with the name '$functionName' is already defined in ${serviceInterface.simpleName.asString()}",
                    function
                )
                return@forEach
            }

            val functionTypeParameterResolver =
                function.typeParameters.toTypeParameterResolver(classTypeParameterResolver)

            val initializer = CodeBlock.Companion.builder()
                .add("{ service, args -> ")
                .add("service.%N(", functionName)

            function.parameters.forEachIndexed { index, parameter ->
                if (index > 0) initializer.add(", ")

                val parameterType = parameter.type
                    .resolve()
                    .toTypeName(functionTypeParameterResolver)

                initializer.add("args[%L] as %T", index, parameterType)
            }

            initializer
                .add(") ")
                .add("}")

            addProperty(
                PropertySpec.Companion.builder(
                    functionName + "Invoker",
                    ClassNames.rpcInvoker.parameterizedBy(serviceInterfaceClassName)
                )
                    .addModifiers(KModifier.PRIVATE)
                    .addAnnotation(
                        AnnotationSpec.Companion.builder(Suppress::class).addMember("%S", "UNCHECKED_CAST").build()
                    )
                    .initializer(initializer.build())
                    .build()
            )
        }
    }

    private fun TypeSpec.Builder.generateCallablesMap(
        serviceInterface: KSClassDeclaration,
        serviceInterfaceClassName: ClassName
    ) {
        val mapType = MAP.parameterizedBy(
            String::class.asTypeName(),
            ClassNames.rpcCallable.parameterizedBy(serviceInterfaceClassName)
        )

        val initializer = CodeBlock.Companion.builder()
            .add("mapOf(")
            .apply {
                serviceInterface.getAllFunctions()
                    .filterNot(::isObjectMethod)
                    .forEachIndexed { index, function ->
                        if (index > 0) add(", ")
                        add(
                            "%S to %T(",
                            function.simpleName.asString(),
                            ClassNames.rpcCallableDefault.parameterizedBy(serviceInterfaceClassName)
                        )
                        add("name = %S,", function.simpleName.asString())

                        val returnType = function.returnType
                        if (returnType == null) {
                            logger.error(
                                "Cannot generate descriptor for function ${function.simpleName.asString()}: no return type",
                                function
                            )
                            return
                        }

                        add("returnType = ").add(returnType.createRabbitRpcTypeConstructor()).add(",")
                        add("invoker = ${function.simpleName.asString()}Invoker,")
                        add("parameters = ").apply {
                            val parameters = function.parameters
                            if (parameters.isEmpty()) {
                                add("%M()", MemberNames.emptyArray)
                            } else {
                                add("%M(", MemberNames.arrayOf)
                                parameters.forEachIndexed { index, parameter ->
                                    if (index > 0) add(", ")
                                    add("%L", parameter.createRabbitRpcParameterConstructor())
                                }
                                add(")")
                            }
                        }
                        add(")")
                    }
            }
            .add(")")
            .build()

        addProperty(
            PropertySpec.Companion.builder("callables", mapType)
                .addModifiers(KModifier.OVERRIDE)
                .initializer(initializer)
                .build()
        )
    }

    private fun KSValueParameter.createRabbitRpcParameterConstructor(): CodeBlock {
        return CodeBlock.Companion.builder()
            .add("%T(", ClassNames.rpcParameterDefault)
            .add("name = %S,", name?.asString() ?: "")
            .add("type = ").add(type.createRabbitRpcTypeConstructor()).add(",")
            .add("isOptional = %L,", hasDefault)
            .add("annotations = %L", annotations.toList().toAnnotationListCode())
            .add(")")
            .build()
    }

    private fun KSTypeReference.createRabbitRpcTypeConstructor(): CodeBlock {
        val typeUseAnnotations = annotations.toList()

        val serializableAnnotations = typeUseAnnotations
            .filter { it.isSerializableAnnotation() }

        val rpcTypeClass =
            if (serializableAnnotations.isNotEmpty()) {
                ClassNames.rpcTypeKrpc
            } else {
                ClassNames.rpcTypeDefault
            }

        return CodeBlock.Companion.builder()
            .add("%T(", rpcTypeClass)
            .add("kType = %M<%T>()", MemberNames.kotlinTypeOf, toAnnotatedTypeName())
            .add(", annotations = %L", annotations.toList().toAnnotationListCode())
            .applyIf(serializableAnnotations.isNotEmpty()) {
                add(", serializers = %L", serializableAnnotations.toSerializerMapCode())
            }
            .add(")")
            .build()
    }

    companion object {
        private fun KSTypeReference.toAnnotatedTypeName(): TypeName {
            val baseType = toTypeName()

            val typeUseAnnotations = annotations
                .map { it.toAnnotationSpec() }
                .toList()

            if (typeUseAnnotations.isEmpty()) {
                return baseType
            }

            return baseType.copy(
                annotations = baseType.annotations + typeUseAnnotations
            )
        }


        private fun List<KSAnnotation>.toSerializerMapCode(): CodeBlock {
            val entries = mapNotNull { annotation ->
                annotation.toSerializerMapEntryCode()
            }

            if (entries.isEmpty()) {
                return CodeBlock.Companion.of("%M()", MemberNames.emptyMap)
            }

            return CodeBlock.Companion.builder()
                .add("%M(\n", MemberNames.mapOf)
                .withIndent {
                    for (entry in entries) {
                        add("%L,\n", entry)
                    }
                }
                .add(")")
                .build()
        }

        private fun KSAnnotation.toSerializerMapEntryCode(): CodeBlock? {
            val serializerType = arguments
                .find { it.name?.asString() == "with" }
                ?.value as? KSType
                ?: return null

            val serializerDeclaration = serializerType.declaration as? KSClassDeclaration
                ?: error(
                    "Expected serializer class declaration for @Serializable.with, " +
                            "but got ${serializerType.declaration.qualifiedName?.asString()}"
                )

            val serializerClassName = serializerDeclaration.toClassName()

            return CodeBlock.Companion.of(
                "%T::class as %T to %L as %T",
                serializerClassName,
                Types.serializerKClassType,
                serializerDeclaration.createSerializerInstanceCode(),
                Types.kSerializerAnyNullable,
            )
        }

        private fun KSClassDeclaration.createSerializerInstanceCode(): CodeBlock = when (classKind) {
            ClassKind.OBJECT -> CodeBlock.Companion.of("%T", toClassName())
            ClassKind.CLASS -> {
                val constructor = primaryConstructor
                    ?: error("Serializer ${qualifiedName?.asString()} must have a primary constructor")

                if (constructor.parameters.isNotEmpty()) {
                    error(
                        "Serializer ${qualifiedName?.asString()} must have a no-arg primary constructor"
                    )
                }

                CodeBlock.Companion.of("%T()", toClassName())
            }

            else -> error("Cannot create serializer instance for class kind ${classKind.name}")

        }

        private fun List<KSAnnotation>.toAnnotationListCode(): CodeBlock {
            if (isEmpty()) {
                return CodeBlock.Companion.of("%M()", MemberNames.emptyList)
            }

            return CodeBlock.Companion.builder()
                .add("%M(\n", MemberNames.listOf)
                .withIndent {
                    for (annotation in this@toAnnotationListCode) {
                        add("%L,\n", annotation.toConstructorCode())
                    }
                }
                .add(")")
                .build()
        }

        private fun KSAnnotation.toConstructorCode(): CodeBlock {
            val annotationType = annotationType.resolve().toTypeName()

            return CodeBlock.Companion.builder()
                .add("%T(", annotationType)
                .apply {
                    arguments.forEachIndexed { index, argument ->
                        if (index > 0) add(", ")

                        val name = argument.name?.asString()

                        if (name != null) {
                            add("%N = ", name)
                        }

                        add("%L", argument.value.toAnnotationValueCode())
                    }
                }
                .add(")")
                .build()
        }

        private fun Any?.toAnnotationValueCode(): CodeBlock = when (this) {
            is String -> CodeBlock.Companion.of("%S", this)
            is Char -> CodeBlock.Companion.of("%L", "'$this'")
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double -> CodeBlock.Companion.of("%L", this)

            is KSType -> CodeBlock.Companion.of("%T::class", this.toTypeName())
            is KSAnnotation -> this.toConstructorCode()

            is KSClassDeclaration -> {
                val parent = parentDeclaration as? KSClassDeclaration
                    ?: error("Enum entry has no parent enum: ${qualifiedName?.asString()}")

                CodeBlock.Companion.of(
                    "%T.%N",
                    parent.toClassName(),
                    simpleName.asString()
                )
            }

            is Array<*> -> {
                if (isEmpty()) {
                    CodeBlock.Companion.of("%M()", MemberNames.emptyArray)
                } else {
                    CodeBlock.Companion.builder()
                        .add("%M(", MemberNames.arrayOf)
                        .apply {
                            this@toAnnotationValueCode.forEachIndexed { index, value ->
                                if (index > 0) add(", ")
                                add("%L", value.toAnnotationValueCode())
                            }
                        }
                        .add(")")
                        .build()
                }
            }

            null -> error("Annotation values cannot be null")
            else -> error("Unsupported annotation argument value: $this (${this::class})")
        }

        private fun KSAnnotation.isSerializableAnnotation(): Boolean {
            val type = annotationType.resolve()
            return type.declaration.qualifiedName?.asString() == Names.KOTLINX_SERIALIZABLE_FQ
        }

        private fun isObjectMethod(function: KSFunctionDeclaration): Boolean {
            val name = function.simpleName.asString()
            if (name == "toString" && function.parameters.isEmpty()) {
                return true
            }

            if (name == "equals" && function.parameters.size == 1) {
                return true
            }

            if (name == "hashCode" && function.parameters.isEmpty()) {
                return true
            }

            return false
        }
    }
}