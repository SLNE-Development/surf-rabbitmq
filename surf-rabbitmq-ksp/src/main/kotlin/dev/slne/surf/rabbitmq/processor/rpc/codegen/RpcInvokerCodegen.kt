package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toTypeName
import dev.slne.surf.rabbitmq.processor.ClassNames
import dev.slne.surf.rabbitmq.processor.rpc.model.RpcFunctionModel
import dev.slne.surf.rabbitmq.processor.rpc.model.RpcServiceModel

class RpcInvokerCodegen {
    fun createInvokerProperty(
        service: RpcServiceModel,
        function: RpcFunctionModel,
    ): PropertySpec {
        val invokerType = ClassNames.rpcInvoker.parameterizedBy(service.serviceClassName)

        return PropertySpec.builder(function.invokerName, invokerType)
            .addModifiers(KModifier.PRIVATE)
            .initializer(
                "%T(::%N)",
                invokerType,
                function.invokerFunctionName,
            )
            .build()
    }

    fun createInvokerFunction(
        service: RpcServiceModel,
        function: RpcFunctionModel,
    ): FunSpec {
        val argsType = ARRAY.parameterizedBy(ANY.copy(nullable = true))

        return FunSpec.builder(function.invokerFunctionName)
            .addModifiers(KModifier.PRIVATE, KModifier.SUSPEND)
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "UNCHECKED_CAST").build())
            .addParameter("service", service.serviceClassName)
            .addParameter("args", argsType)
            .returns(ANY.copy(nullable = true))
            .addCode(createInvokerFunctionBody(function))
            .build()
    }


    private fun createInvokerFunctionBody(function: RpcFunctionModel): CodeBlock {
        return CodeBlock.builder()
            .add("return service.%N(", function.name)
            .apply {
                if (function.parameters.isNotEmpty()) {
                    add("\n")
                    withIndent {
                        function.parameters.forEachIndexed { index, parameter ->
                            if (index > 0) add(",\n")

                            val parameterType = parameter.type
                                .resolve()
                                .toTypeName(function.typeParameterResolver)

                            add("args[%L] as %T", index, parameterType)
                        }

                        add(",")
                    }

                    add("\n")
                }
            }
            .add(")\n")
            .build()
    }
}