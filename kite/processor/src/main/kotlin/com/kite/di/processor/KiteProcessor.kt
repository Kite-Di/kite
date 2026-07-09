package com.kite.di.processor

import com.kite.di.graph.GraphJson
import com.kite.di.graph.Key
import com.kite.di.processor.codegen.FactoryGenerator
import com.kite.di.processor.codegen.RegistryGenerator
import com.kite.di.processor.codegen.RuntimeNames
import com.kite.di.processor.export.GraphJsonExporter
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.scan.BindingScanner
import com.kite.di.processor.validate.GraphValidator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec

/**
 * KSP options (set in the consumer's `ksp { arg(...) }` block):
 * - `kite.module`     Gradle path for registry naming/provenance (e.g. ":app")
 * - `kite.rootDir`    repo root; provenance paths are relative to it
 * - `kite.aggregate`  "true" in the application module: also emit MergedRegistry + graph.json
 * - `kite.appId`      applicationId stamped into graph.json
 * - `kite.variant`    variant name stamped into graph.json
 * - `kite.stripProvenance`  "true" for release variants: no file/line strings in
 *                         generated registries and no graph.json — the dependency
 *                         graph is a development-only artifact and must never ship
 *                         to end users
 * - `kite.graphOut`   absolute file path for graph.json. Written into the build
 *                         directory for the host-side board; NEVER packaged into an APK.
 * - `kite.embedGraph` "true" opts into ALSO emitting graph.json as a java resource
 *                         (packaged into the APK) — only for teams deliberately using
 *                         the on-device inspector. Default off.
 */
class ProcessorOptions(options: Map<String, String>) {
    val moduleName: String = options["kite.module"] ?: ":unknown"
    val rootDir: String = options["kite.rootDir"] ?: ""
    val aggregate: Boolean = options["kite.aggregate"] == "true"
    val appId: String = options["kite.appId"] ?: "unknown"
    val variant: String = options["kite.variant"] ?: "main"
    val stripProvenance: Boolean = options["kite.stripProvenance"] == "true"
    val graphOut: String? = options["kite.graphOut"]
    val embedGraph: Boolean = options["kite.embedGraph"] == "true"
}

class KiteProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList() // generated files carry none of our annotations
        invoked = true

        val options = ProcessorOptions(environment.options)
        val scan = BindingScanner(resolver, options).scan()
        val issues = scan.issues + GraphValidator.validate(scan)

        var hasErrors = false
        for (issue in issues) when (issue.severity) {
            Severity.ERROR -> {
                hasErrors = true
                environment.logger.error(issue.message)
            }
            Severity.WARNING -> environment.logger.warn(issue.message)
        }
        if (hasErrors) return emptyList() // no codegen on a broken graph
        if (scan.bindings.isEmpty() && scan.memberInjects.isEmpty()) {
            // No DI declarations in this compilation. Crucially this also covers the
            // unitTest/androidTest KSP runs of the app module: generating an (empty)
            // aggregate there would shadow the real one on the test classpath.
            return emptyList()
        }

        generate(resolver, scan, options)
        return emptyList()
    }

    private fun generate(resolver: Resolver, scan: ScanResult, options: ProcessorOptions) {
        val deps = Dependencies.ALL_FILES
        val availableKeys: Set<Key> = buildSet {
            for (b in scan.bindings) {
                add(b.key)
                addAll(b.extraKeys)
            }
            addAll(BUILT_IN_KEYS.keys)
        }

        for (binding in scan.bindings) {
            write(FactoryGenerator.factoryFile(binding, availableKeys), deps)
        }
        for (target in scan.memberInjects) {
            write(FactoryGenerator.memberInjectorFile(target), deps)
        }
        write(
            RegistryGenerator.registryFile(
                options.moduleName,
                scan.bindings,
                scan.memberInjects,
                includeProvenance = !options.stripProvenance,
            ),
            deps,
        )

        if (options.aggregate) {
            val classpathRegistries = findClasspathRegistries(resolver)
            write(
                RegistryGenerator.mergedRegistryFile(
                    ownRegistryName = RegistryGenerator.registryName(options.moduleName),
                    classpathRegistryFqns = classpathRegistries,
                ),
                deps,
            )
            if (options.stripProvenance) return // no graph artifact at all for user-facing builds
            val snapshot = GraphJsonExporter.export(scan, options.appId, options.variant)
            val text = GraphJson.encodePretty(snapshot) + "\n"
            // Development-only artifact: written into the build directory for the
            // host-side board, deliberately NOT a java resource — resources get
            // packaged into the APK and the graph must never ship to users.
            options.graphOut?.let { path ->
                java.io.File(path).apply { parentFile?.mkdirs() }.writeText(text)
            }
            if (options.embedGraph) {
                // Explicit opt-in for the on-device inspector workflow.
                environment.codeGenerator
                    .createNewFileByPath(deps, "kite/graph", extensionName = "json")
                    .bufferedWriter()
                    .use { it.write(text) }
            }
        }
    }

    /** Registries generated by other Gradle modules, visible on the compile classpath. */
    @OptIn(com.google.devtools.ksp.KspExperimental::class)
    private fun findClasspathRegistries(resolver: Resolver): List<String> =
        resolver.getDeclarationsFromPackage(RuntimeNames.GENERATED_PACKAGE)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration ->
                declaration.superTypes.any {
                    it.resolve().declaration.qualifiedName?.asString() ==
                        "com.kite.di.runtime.BindingRegistry"
                }
            }
            .mapNotNull { it.qualifiedName?.asString() }
            .filterNot { it.endsWith(".MergedRegistry") }
            .toList()

    private fun write(file: FileSpec, deps: Dependencies) {
        environment.codeGenerator
            .createNewFile(deps, file.packageName, file.name, extensionName = "kt")
            .bufferedWriter()
            .use { file.writeTo(it) }
    }
}

class KiteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KiteProcessor(environment)
}
