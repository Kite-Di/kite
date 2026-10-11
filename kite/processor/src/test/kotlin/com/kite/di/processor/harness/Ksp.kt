package com.kite.di.processor.harness

import com.kite.di.processor.KiteProcessorProvider
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs the real processor over Kotlin sources — inference, rules, validation and
 * codegen — as the module [module], with [upstream] modules already built on its
 * classpath. Only the processor's verdict is judged: an application module's
 * generated `Graph` and ViewModel adapters need Android to compile, which a JVM test
 * does not have, so what it generated is checked as text.
 */
@OptIn(ExperimentalCompilerApi::class)
fun ksp(
    vararg sources: Pair<String, String>,
    aggregate: Boolean = true,
    module: String = ":app",
    upstream: List<Upstream> = emptyList(),
    options: Map<String, String> = emptyMap(),
): KspRun {
    val log = ByteArrayOutputStream()
    val compilation = compilation(sources.toList(), module, aggregate, upstream, options, log)
    // The exit code is the compiler's: KSP2 compiles the generated sources whatever
    // `withCompilation` says, and an application's cannot compile here.
    compilation.compile()
    return KspRun(
        log = log.toString(),
        generated = compilation.kspSourcesDir.walkTopDown().filter(File::isFile)
            .associate { it.name to it.readText() },
    )
}

/**
 * A library module built ahead of the one under test: processed *and* compiled, so its
 * generated registry reaches the next module's classpath exactly as Gradle would put
 * it there. It must not declare ViewModels — their adapters need Android.
 */
@OptIn(ExperimentalCompilerApi::class)
fun upstream(
    module: String,
    vararg sources: Pair<String, String>,
    upstream: List<Upstream> = emptyList(),
    options: Map<String, String> = emptyMap(),
): Upstream {
    val log = ByteArrayOutputStream()
    val compilation = compilation(sources.toList(), module, aggregate = false, upstream, options, log)
    val result = compilation.compile()
    check(result.exitCode == KotlinCompilation.ExitCode.OK) { "upstream $module did not build:\n$log" }
    return Upstream(module, upstream.flatMap { it.classpath } + compilation.classesDir)
}

class Upstream(val module: String, val classpath: List<File>)

/**
 * Compiles one generated file on its own, against the runtime and [upstream] — a
 * module of its own, so Kotlin's `internal` is enforced exactly as in the module that
 * generated it. For generated files that need no Android (registries); returns the
 * compiler's errors.
 */
@OptIn(ExperimentalCompilerApi::class)
fun compileErrors(name: String, code: String, upstream: List<Upstream>): List<String> {
    val log = ByteArrayOutputStream()
    KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin(name, code))
        inheritClassPath = true
        classpaths = listOf(Runtime.classes) + upstream.flatMap { it.classpath }.distinct()
        messageOutputStream = log
    }.compile()
    return log.toString().lineSequence().filter { it.startsWith("e: ") }.toList()
}

@OptIn(ExperimentalCompilerApi::class)
private fun compilation(
    sources: List<Pair<String, String>>,
    module: String,
    aggregate: Boolean,
    upstream: List<Upstream>,
    options: Map<String, String>,
    log: ByteArrayOutputStream,
) = KotlinCompilation().apply {
    this.sources = sources.map { (name, code) -> SourceFile.kotlin(name, code.trimIndent()) }
    inheritClassPath = true
    val projectDirs = upstream.flatMap { it.classpath }.distinct()
    classpaths = listOf(Runtime.classes) + projectDirs
    messageOutputStream = log
    useKsp2()
    configureKsp {
        symbolProcessorProviders += KiteProcessorProvider()
        withCompilation = true
        processorOptions += mapOf(
            "kite.module" to module,
            "kite.aggregate" to aggregate.toString(),
            // The build's answer to "which classes are the project's": the upstream
            // modules' outputs, as the plugin passes them.
            "kite.projectClasspath" to projectDirs.joinToString(File.pathSeparator),
        ) + options
    }
}

class KspRun(val log: String, val generated: Map<String, String>) {
    /** The processor's own errors, without the compiler's complaints about Android. */
    val errors: List<String> = kspMessages("e: [ksp]")
    val warnings: List<String> = kspMessages("w: [ksp]")
    val ok: Boolean get() = errors.isEmpty()

    /** Each message with its continuation lines (hints, candidates) joined back on. */
    private fun kspMessages(prefix: String): List<String> {
        val messages = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in log.lineSequence()) {
            when {
                line.startsWith(prefix) -> current = StringBuilder(line.removePrefix(prefix).trim()).also { messages += "" }
                line.matches(Regex("^[ewiv]: .*")) -> current = null
                current != null -> current.append('\n').append(line)
            }
            if (current != null) messages[messages.lastIndex] = current.toString()
        }
        return messages
    }

    fun file(name: String): String = generated[name]
        ?: throw AssertionError("no $name generated; got ${generated.keys.sorted()}\n$log")

    override fun toString(): String = log
}

/**
 * The runtime's JVM core, compiled once from its real sources, plus stand-ins for the
 * androidx types user code extends. On every compilation's classpath — a library,
 * never the project's own.
 */
@OptIn(ExperimentalCompilerApi::class)
private object Runtime {
    private val CORE = listOf("Core.kt", "Supplies.kt", "ScopeTree.kt", "observe/GraphEvents.kt")

    val classes: File by lazy {
        val dir = File(checkNotNull(System.getProperty("kite.runtimeSources")) { "kite.runtimeSources not set" })
        val log = ByteArrayOutputStream()
        val compilation = KotlinCompilation().apply {
            sources = CORE.map { SourceFile.fromPath(dir.resolve(it)) } + SourceFile.kotlin(
                "AndroidxStubs.kt",
                """
                package androidx.lifecycle
                abstract class ViewModel
                class SavedStateHandle
                """.trimIndent(),
            )
            inheritClassPath = true
            messageOutputStream = log
        }
        val result = compilation.compile()
        check(result.exitCode == KotlinCompilation.ExitCode.OK) { "the runtime core did not compile:\n$log" }
        compilation.classesDir
    }
}
