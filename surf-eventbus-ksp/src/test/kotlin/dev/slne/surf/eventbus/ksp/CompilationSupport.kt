package dev.slne.surf.eventbus.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import dev.slne.surf.eventbus.ksp.processor.query.QueryServiceProcessorProvider
import dev.slne.surf.eventbus.rabbitmq.processor.rpc.RpcServiceProcessorProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.ByteArrayOutputStream

data class CompileResult(val succeeded: Boolean, val messages: String)

/** Compiles [source] with the RPC/query KSP processors attached; never runs the code. */
@OptIn(ExperimentalCompilerApi::class)
fun compile(source: String): CompileResult {
    val output = ByteArrayOutputStream()

    val compilation = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Source.kt", source))
        configureKsp {
            symbolProcessorProviders.add(RpcServiceProcessorProvider())
            symbolProcessorProviders.add(QueryServiceProcessorProvider())
        }
        inheritClassPath = true
        messageOutputStream = output
        verbose = false
    }

    val result = compilation.compile()

    return CompileResult(
        succeeded = result.exitCode == KotlinCompilation.ExitCode.OK,
        messages = output.toString(Charsets.UTF_8),
    )
}
