package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dev.slne.surf.rabbitmq.processor.ClassNames
import dev.slne.surf.rabbitmq.processor.MemberNames
import dev.slne.surf.rabbitmq.processor.rpc.model.RpcFunctionModel
import dev.slne.surf.rabbitmq.processor.rpc.model.RpcServiceModel

class RpcCallableCodegen {

    companion object {
        private const val CALLABLES_FACTORY_NAME = "createCallables"
    }

    fun createCallablesProperty(service: RpcServiceModel): PropertySpec {
        return PropertySpec.builder("callables", service.callablesMapType())
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%N()", CALLABLES_FACTORY_NAME)
            .build()
    }

    fun createCallablesFunction(service: RpcServiceModel): FunSpec {
        return FunSpec.builder(CALLABLES_FACTORY_NAME)
            .addModifiers(KModifier.PRIVATE)
            .returns(service.callablesMapType())
            .addCode("return %L\n", createCallablesMap(service))
            .build()
    }

    private fun RpcServiceModel.callablesMapType(): TypeName {
        return MAP.parameterizedBy(
            String::class.asTypeName(),
            ClassNames.rpcCallable.parameterizedBy(serviceClassName),
        )
    }

    private fun createCallablesMap(service: RpcServiceModel): CodeBlock {
        if (service.functions.isEmpty()) {
            return CodeBlock.of("%M()", MemberNames.emptyMap)
        }

        return CodeBlock.builder()
            .add("%M(\n", MemberNames.mapOf)
            .withIndent {
                service.functions.forEachIndexed { index, function ->
                    if (index > 0) add(",\n")
                    add("%S to %L", function.name, createCallable(service, function))
                }
            }
            .add("\n)")
            .build()
    }

    private fun createCallable(
        service: RpcServiceModel,
        function: RpcFunctionModel,
    ): CodeBlock {
        return CodeBlock.builder()
            .add("%T(\n", ClassNames.rpcCallableDefault.parameterizedBy(service.serviceClassName))
            .withIndent {
                add("name = %S,\n", function.name)
                add(
                    "returnType = %L,\n",
                    function.returnType.createRabbitRpcTypeConstructor(function.typeParameterResolver),
                )
                add("invoker = %N,\n", function.invokerName)
                add("parameters = %L,\n", createParametersArray(function))
            }
            .add(")")
            .build()
    }

    private fun createParametersArray(function: RpcFunctionModel): CodeBlock {
        if (function.parameters.isEmpty()) {
            return CodeBlock.of("%M()", MemberNames.emptyArray)
        }

        return CodeBlock.builder()
            .add("%M(\n", MemberNames.arrayOf)
            .withIndent {
                function.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) add(",\n")
                    add("%L", parameter.createRabbitRpcParameterConstructor(function))
                }
            }
            .add("\n)")
            .build()
    }

    private fun KSValueParameter.createRabbitRpcParameterConstructor(function: RpcFunctionModel): CodeBlock {
        return CodeBlock.builder()
            .add("%T(\n", ClassNames.rpcParameterDefault)
            .indent()
            .add("name = %S,\n", name?.asString().orEmpty())
            .add("type = %L,\n", type.createRabbitRpcTypeConstructor(function.typeParameterResolver))
            .add("isOptional = %L,\n", hasDefault)
            .add("annotations = %L,\n", annotations.toList().toAnnotationListCode())
            .unindent()
            .add(")")
            .build()
    }
}