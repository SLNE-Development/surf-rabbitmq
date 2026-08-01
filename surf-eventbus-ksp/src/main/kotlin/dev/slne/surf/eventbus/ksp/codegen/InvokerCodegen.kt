package dev.slne.surf.eventbus.ksp.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.withIndent
import dev.slne.surf.eventbus.ksp.model.ServiceFunctionModel
import dev.slne.surf.eventbus.ksp.model.ServiceModel

/** Emits the generated method handle a descriptor invokes an implementation through. */
class InvokerCodegen {

    fun createInvokerProperty(
        service: ServiceModel,
        function: ServiceFunctionModel,
    ): PropertySpec {
        val invokerType = ClassNames.serviceInvoker.parameterizedBy(service.serviceClassName)

        return PropertySpec.builder(function.invokerName, invokerType)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%T(::%N)", invokerType, function.invokerFunctionName)
            .build()
    }

    fun createInvokerFunction(
        service: ServiceModel,
        function: ServiceFunctionModel,
    ): FunSpec {
        return FunSpec.builder(function.invokerFunctionName)
            .addModifiers(KModifier.PRIVATE, KModifier.SUSPEND)
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class).addMember("%S", "UNCHECKED_CAST").build()
            )
            .addParameter("service", service.serviceClassName)
            .addParameter("args", Types.anyNullableArray)
            .returns(ANY.copy(nullable = true))
            .addCode(createInvokerFunctionBody(function))
            .build()
    }

    private fun createInvokerFunctionBody(function: ServiceFunctionModel): CodeBlock {
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
