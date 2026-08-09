package dev.slne.surf.eventbus.ksp.codegen

import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.withIndent
import dev.slne.surf.eventbus.ksp.model.ServiceFunctionModel
import dev.slne.surf.eventbus.ksp.model.ServiceModel

/** Emits a descriptor's `callables` map: one [ServiceCallableDefault] per contract method. */
class CallableCodegen {

    companion object {
        private const val CALLABLES_FACTORY_NAME = "createCallables"
    }

    fun createCallablesProperty(service: ServiceModel): PropertySpec {
        return PropertySpec.builder("callables", service.callablesMapType())
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%N()", CALLABLES_FACTORY_NAME)
            .build()
    }

    fun createCallablesFunction(service: ServiceModel): FunSpec {
        return FunSpec.builder(CALLABLES_FACTORY_NAME)
            .addModifiers(KModifier.PRIVATE)
            .returns(service.callablesMapType())
            .addCode("return %L\n", createCallablesMap(service))
            .build()
    }

    fun createGetCallableFunction(service: ServiceModel): FunSpec {
        return FunSpec.builder("getCallable")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("name", String::class)
            .returns(
                ClassNames.serviceCallable
                    .parameterizedBy(service.serviceClassName)
                    .copy(nullable = true)
            )
            .addStatement("return callables[name]")
            .build()
    }

    private fun ServiceModel.callablesMapType(): TypeName {
        return MAP.parameterizedBy(
            String::class.asTypeName(),
            ClassNames.serviceCallable.parameterizedBy(serviceClassName),
        )
    }

    private fun createCallablesMap(service: ServiceModel): CodeBlock {
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
        service: ServiceModel,
        function: ServiceFunctionModel,
    ): CodeBlock {
        return CodeBlock.builder()
            .add("%T(\n", ClassNames.serviceCallableDefault.parameterizedBy(service.serviceClassName))
            .withIndent {
                add("name = %S,\n", function.name)
                add(
                    "returnType = %L,\n",
                    function.returnType.createServiceTypeConstructor(function.typeParameterResolver),
                )
                add("invoker = %N,\n", function.invokerName)
                add("parameters = %L,\n", createParametersArray(function))
                add("fireAndForget = %L,\n", function.fireAndForget)
            }
            .add(")")
            .build()
    }

    private fun createParametersArray(function: ServiceFunctionModel): CodeBlock {
        if (function.parameters.isEmpty()) {
            return CodeBlock.of("%M()", MemberNames.emptyArray)
        }

        return CodeBlock.builder()
            .add("%M(\n", MemberNames.arrayOf)
            .withIndent {
                function.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) add(",\n")
                    add("%L", parameter.createServiceParameterConstructor(function))
                }
            }
            .add("\n)")
            .build()
    }

    private fun KSValueParameter.createServiceParameterConstructor(
        function: ServiceFunctionModel,
    ): CodeBlock {
        return CodeBlock.builder()
            .add("%T(\n", ClassNames.serviceParameterDefault)
            .indent()
            .add("name = %S,\n", name?.asString().orEmpty())
            .add("type = %L,\n", type.createServiceTypeConstructor(function.typeParameterResolver))
            .add("isOptional = %L,\n", hasDefault)
            .add("annotations = %L,\n", annotations.toList().toAnnotationListCode())
            .unindent()
            .add(")")
            .build()
    }
}
