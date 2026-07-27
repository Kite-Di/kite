package com.kite.di.processor

import com.kite.di.graph.GraphJson
import com.kite.di.graph.Key
import com.kite.di.processor.codegen.AdapterGenerator
import com.kite.di.processor.codegen.FactoryGenerator
import com.kite.di.processor.codegen.GraphGenerator
import com.kite.di.processor.codegen.RegistryGenerator
import com.kite.di.processor.codegen.RuntimeNames
import com.kite.di.processor.export.GraphJsonExporter
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.GraphArg
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.scan.GraphRules
import com.kite.di.processor.scan.GraphRulesParser
import com.kite.di.processor.scan.InferenceScanner
import com.kite.di.processor.validate.GraphValidator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import java.io.File

/**
 * KSP options (set by the Kite Gradle plugin):
 * - `kite.module`     Gradle path for registry naming/provenance (e.g. ":app")
 * - `kite.rootDir`    repo root; provenance paths are relative to it
 * - `kite.rules`      absolute path of the module's graph.rules (may not exist)
 * - `kite.aggregate`  "true" in the application module: also emit MergedRegistry,
 *                         Graph (startup façade) and graph.json
 * - `kite.appId`      applicationId stamped into graph.json
 * - `kite.variant`    variant name stamped into graph.json
 * - `kite.skip`       "true" for unit/androidTest compilations: inference over
 *                         test sources would shadow the main graph
 * - `kite.stripProvenance`  "true" for release variants: no file/line strings in
 *                         generated registries and no graph.json — the dependency
 *                         graph is a development-only artifact and must never ship
 * - `kite.graphOut`   absolute file path for graph.json. Written into the build
 *                         directory for the host-side board; NEVER packaged into an APK.
 * - `kite.embedGraph` "true" opts into ALSO emitting graph.json as a java resource
 *                         (packaged into the APK) — only for teams deliberately using
 *                         the on-device inspector. Default off.
 */
class ProcessorOptions(options: Map<String, String>) {
    val moduleName: String = options["kite.module"] ?: ":unknown"
    val rootDir: String = options["kite.rootDir"] ?: ""
    val rulesPath: String? = options["kite.rules"]
    val aggregate: Boolean = options["kite.aggregate"] == "true"
    val appId: String = options["kite.appId"] ?: "unknown"
    val variant: String = options["kite.variant"] ?: "main"
    val skip: Boolean = options["kite.skip"] == "true"
    /** "true" when the module has Compose enabled: ViewModels also get @Composable remember adapters. */
    val compose: Boolean = options["kite.compose"] == "true"
    val stripProvenance: Boolean = options["kite.stripProvenance"] == "true"
    val graphOut: String? = options["kite.graphOut"]
    val embedGraph: Boolean = options["kite.embedGraph"] == "true"
}

class KiteProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList() // single full-inference pass per compilation
        invoked = true

        val options = ProcessorOptions(environment.options)
        if (options.skip) return emptyList() // test compilations: the main graph already exists

        val rules = options.rulesPath
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { GraphRulesParser.parse(it.readText(), it.name) }
            ?: GraphRules.EMPTY

        val scan = InferenceScanner(resolver, options, rules).scan()
        if (scan.bindings.isEmpty() && scan.viewModels.isEmpty() && rules == GraphRules.EMPTY) {
            // Nothing inferable in this compilation (e.g. an interfaces-only module).
            return emptyList()
        }

        val issues = rules.issues + scan.issues + GraphValidator.validate(scan)
        var hasErrors = false
        for (issue in issues) when (issue.severity) {
            Severity.ERROR -> {
                hasErrors = true
                environment.logger.error(issue.message)
            }
            Severity.WARNING -> environment.logger.warn(issue.message)
        }
        if (hasErrors) return emptyList() // no codegen on a broken graph

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
            for (set in scan.setBindings) add(set.key)
            for (arg in scan.graphArgs) add(arg.key)
            addAll(BUILT_IN_KEYS.keys)
        }

        for (binding in scan.bindings) {
            write(FactoryGenerator.factoryFile(binding, availableKeys), deps)
        }
        for (vm in scan.viewModels) {
            write(AdapterGenerator.adapterFile(vm, compose = options.compose), deps)
        }
        write(
            RegistryGenerator.registryFile(
                options.moduleName,
                scan.bindings,
                scan.setBindings,
                scan.graphArgs,
                includeProvenance = !options.stripProvenance,
            ),
            deps,
        )

        if (options.aggregate) {
            val classpathRegistries = findClasspathRegistries(resolver)
            write(
                RegistryGenerator.mergedRegistryFile(
                    ownRegistryName = RegistryGenerator.registryName(options.moduleName),
                    classpathRegistryFqns = classpathRegistries.mapNotNull { it.qualifiedName?.asString() },
                ),
                deps,
            )

            // Graph.start parameters: this module's graph arguments plus every
            // classpath module's, read from their registries' @GraphArgs annotations.
            val allArgs = mutableMapOf<String, GraphArg>()
            for (arg in scan.graphArgs + classpathRegistries.flatMap(::classpathGraphArgs)) {
                val existing = allArgs[arg.name]
                if (existing != null && existing.type.fqn != arg.type.fqn) {
                    environment.logger.error(
                        "Graph argument '${arg.name}' declared with two types across modules: " +
                            "${existing.type.fqn} and ${arg.type.fqn} — same name means same argument; rename one parameter.",
                    )
                    return
                }
                allArgs.putIfAbsent(arg.name, arg)
            }
            write(GraphGenerator.graphFile(allArgs.values.toList(), includeProvenance = !options.stripProvenance), deps)

            if (options.stripProvenance) return // no graph artifact at all for user-facing builds
            val snapshot = GraphJsonExporter.export(scan, options.appId, options.variant)
            val text = GraphJson.encodePretty(snapshot) + "\n"
            // Development-only artifact: written into the build directory for the
            // host-side board, deliberately NOT a java resource — resources get
            // packaged into the APK and the graph must never ship to users.
            options.graphOut?.let { path ->
                File(path).apply { parentFile?.mkdirs() }.writeText(text)
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
    private fun findClasspathRegistries(resolver: Resolver): List<KSClassDeclaration> =
        resolver.getDeclarationsFromPackage(RuntimeNames.GENERATED_PACKAGE)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration ->
                declaration.superTypes.any {
                    it.resolve().declaration.qualifiedName?.asString() ==
                        "com.kite.di.runtime.BindingRegistry"
                }
            }
            .filterNot { it.simpleName.asString() == "MergedRegistry" }
            .toList()

    /** Reads a classpath registry's `@GraphArgs(names, types)` back into the model. */
    private fun classpathGraphArgs(registry: KSClassDeclaration): List<GraphArg> =
        registry.annotations
            .filter { it.shortName.asString() == RuntimeNames.GRAPH_ARGS.simpleName }
            .flatMap { annotation ->
                val names = annotation.arguments.firstOrNull { it.name?.asString() == "names" }?.value as? List<*>
                    ?: emptyList<Any>()
                val types = annotation.arguments.firstOrNull { it.name?.asString() == "types" }?.value as? List<*>
                    ?: emptyList<Any>()
                names.zip(types).mapNotNull { (rawName, rawType) ->
                    val name = rawName as? String ?: return@mapNotNull null
                    val declaration = (rawType as? KSType)?.declaration ?: return@mapNotNull null
                    val pkg = declaration.packageName.asString()
                    val qualified = declaration.qualifiedName?.asString() ?: return@mapNotNull null
                    GraphArg(name, TypeRef(pkg, qualified.removePrefix(pkg).trimStart('.').split('.')), emptyList())
                }
            }
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
