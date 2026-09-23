package com.kite.di.processor

import com.kite.di.graph.DecisionsJson
import com.kite.di.graph.GraphJson
import com.kite.di.graph.Key
import com.kite.di.processor.codegen.AdapterGenerator
import com.kite.di.processor.codegen.FactoryGenerator
import com.kite.di.processor.codegen.GraphGenerator
import com.kite.di.processor.codegen.KeyHandleGenerator
import com.kite.di.processor.codegen.RegistryGenerator
import com.kite.di.processor.codegen.RuntimeNames
import com.kite.di.processor.export.DecisionsExporter
import com.kite.di.processor.export.GraphJsonExporter
import com.kite.di.graph.ScopeDef
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.ClasspathBinding
import com.kite.di.processor.model.ClasspathIndex
import com.kite.di.processor.model.GraphArg
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.scan.InferenceScanner
import com.kite.di.processor.scan.RuleSet
import com.kite.di.processor.scan.RulesScanner
import com.kite.di.processor.validate.ClosureValidator
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
 * - `kite.decisionsOut` absolute file path for decisions.json:
 *                         pending decision cards, written even when inference fails —
 *                         empty on success. Dev-only, same rules as graph.json.
 * The module's decisions themselves are `@Root`/`@Bind`/`@Scoped` annotations on a
 * holder object in this compilation's sources — no file option.
 */
class ProcessorOptions(options: Map<String, String>) {
    val moduleName: String = options["kite.module"] ?: ":unknown"
    val rootDir: String = options["kite.rootDir"] ?: ""
    val aggregate: Boolean = options["kite.aggregate"] == "true"
    val appId: String = options["kite.appId"] ?: "unknown"
    val variant: String = options["kite.variant"] ?: "main"
    val skip: Boolean = options["kite.skip"] == "true"
    /** "true" when the module has Compose enabled: ViewModels also get @Composable remember adapters. */
    val compose: Boolean = options["kite.compose"] == "true"
    val stripProvenance: Boolean = options["kite.stripProvenance"] == "true"
    val graphOut: String? = options["kite.graphOut"]
    val decisionsOut: String? = options["kite.decisionsOut"]
}

class KiteProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList() // single full-inference pass per compilation
        invoked = true

        val options = ProcessorOptions(environment.options)
        if (options.skip) return emptyList() // test compilations: the main graph already exists

        val rules = RulesScanner(resolver, options).scan()
        val classpathRegistries = findClasspathRegistries(resolver)
        val classpath = classpathIndex(classpathRegistries)
        val scan = InferenceScanner(resolver, options, rules, classpath).scan()
        if (scan.bindings.isEmpty() && scan.viewModels.isEmpty() && rules.isEmpty() && !options.aggregate) {
            // Nothing inferable in this compilation (e.g. an interfaces-only module).
            // The application module is exempt: a shell that owns no injectable class
            // of its own still has to get its Graph and merged registry.
            return emptyList()
        }

        val issues = rules.issues + scan.issues +
            GraphValidator.validate(scan, aggregate = options.aggregate) +
            crossModuleScopeCollisions(scan, classpath) +
            // Only the app module merges other modules' registries, so only it can
            // notice that one of them is missing from the merge.
            (if (options.aggregate) ClosureValidator.validate(resolver, classpath) else emptyList())
        var hasErrors = false
        for (issue in issues) when (issue.severity) {
            Severity.ERROR -> {
                hasErrors = true
                environment.logger.error(issue.message)
            }
            Severity.WARNING -> environment.logger.warn(issue.message)
        }
        // Written even (especially) on a failed build: pending decisions become
        // board cards; an empty list on success clears them.
        writeDecisions(scan, rules, options)
        if (hasErrors) return emptyList() // no codegen on a broken graph

        generate(classpathRegistries, scan, options)
        return emptyList()
    }

    /** decisions.json — a development-only artifact like graph.json, never packaged. */
    private fun writeDecisions(scan: ScanResult, rules: RuleSet, options: ProcessorOptions) {
        if (options.stripProvenance) return // user-facing builds: no dev artifacts at all
        val path = options.decisionsOut ?: return
        val text = DecisionsJson.encodePretty(DecisionsExporter.export(scan.decisions, rules, options.moduleName))
        File(path).apply { parentFile?.mkdirs() }.writeText(text + "\n")
    }

    private fun generate(classpathRegistries: List<KSClassDeclaration>, scan: ScanResult, options: ProcessorOptions) {
        val deps = Dependencies.ALL_FILES
        val availableKeys: Set<Key> = buildSet {
            for (b in scan.bindings) {
                add(b.key)
                addAll(b.extraKeys)
            }
            for (set in scan.setBindings) add(set.key)
            for (arg in scan.graphArgs) add(arg.key)
            addAll(BUILT_IN_KEYS.keys)
            addAll(scan.classpathKeys.keys) // other modules' registries
        }

        for (binding in scan.bindings) {
            write(FactoryGenerator.factoryFile(binding, availableKeys), deps)
            // Only internal bindings need one — see KeyHandleGenerator.
            KeyHandleGenerator.handleFile(binding)?.let { write(it, deps) }
        }
        for (vm in scan.viewModels) {
            write(AdapterGenerator.adapterFile(vm, compose = options.compose), deps)
        }
        write(
            RegistryGenerator.registryFile(
                options.moduleName,
                scan.bindings,
                // Only the application composes sets: it is the one compilation that
                // sees every contributor. Elsewhere the parameter stays an edge on the
                // set's key and this module registers nothing for it — two modules
                // registering the same set key is a duplicate at startup.
                if (options.aggregate) scan.setBindings else emptyList(),
                scan.graphArgs,
                ambiguousInterfaces = scan.ambiguousInterfaces,
                includeProvenance = !options.stripProvenance,
            ),
            deps,
        )

        if (!options.aggregate) {
            // Library modules write their graph *fragment* for the board — the
            // board server merges every module's fragment into one canvas.
            // Same rules as the app graph: dev-only, never packaged.
            if (!options.stripProvenance) {
                options.graphOut?.let { path ->
                    val fragment = GraphJsonExporter.export(scan, options.appId, options.variant)
                    File(path).apply { parentFile?.mkdirs() }
                        .writeText(GraphJson.encodePretty(fragment) + "\n")
                }
            }
            return
        }

        run {
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
            // host-side board, and never as a java resource — resources are packaged
            // into the APK, and the graph does not go on a device at all. The board
            // reads it here; the device only ever reports runtime events.
            options.graphOut?.let { path ->
                File(path).apply { parentFile?.mkdirs() }.writeText(text)
            }
        }
    }

    /**
     * Reads every classpath registry's `@ProvidedKeys` into the model: which keys
     * other source modules provide (with scopes), and which interfaces they left
     * ambiguous. This is what makes a constructor parameter of another module's
     * type a cross-module edge instead of a bubbled graph argument.
     */
    private fun classpathIndex(registries: List<KSClassDeclaration>): ClasspathIndex {
        val provided = mutableMapOf<String, ClasspathBinding>()
        val providedQualified = mutableMapOf<String, ClasspathBinding>()
        val ambiguous = mutableMapOf<String, String>()
        for (registry in registries) {
            val annotation = registry.annotations
                .firstOrNull { it.shortName.asString() == RuntimeNames.PROVIDED_KEYS.simpleName }
                ?: continue

            fun arg(name: String): Any? =
                annotation.arguments.firstOrNull { it.name?.asString() == name }?.value

            fun fqns(name: String): List<String?> = (arg(name) as? List<*>).orEmpty()
                .map { (it as? KSType)?.declaration?.qualifiedName?.asString() }

            val module = arg("module") as? String ?: ":unknown"
            val scopeNames = (arg("scopeNames") as? List<*>).orEmpty().map { it as? String ?: "" }
            val scopeLevels: List<Int> = when (val raw = arg("scopeLevels")) {
                is IntArray -> raw.toList()
                is List<*> -> raw.map { it as? Int ?: Int.MIN_VALUE }
                else -> emptyList()
            }
            val provenance = (arg("provenance") as? List<*>).orEmpty().map { it as? String }
            val qualifiers = (arg("qualifiers") as? List<*>).orEmpty().map { it as? String ?: "" }
            fqns("types").forEachIndexed { i, fqn ->
                if (fqn == null) return@forEachIndexed
                val name = scopeNames.getOrNull(i).orEmpty()
                val level = scopeLevels.getOrNull(i) ?: Int.MIN_VALUE
                val scope = if (name.isEmpty() || level == Int.MIN_VALUE) null else ScopeDef(name, level)
                val binding = ClasspathBinding(scope, module, provenance.getOrNull(i))
                val qualifier = qualifiers.getOrNull(i).orEmpty()
                if (qualifier.isEmpty()) {
                    provided.putIfAbsent(fqn, binding)
                } else {
                    // A marked implementation claims "qualifier@type", never the bare
                    // type — that is what lets sibling modules each own one.
                    providedQualified.putIfAbsent("$qualifier@$fqn", binding)
                }
            }
            for (fqn in fqns("ambiguous")) {
                if (fqn != null) ambiguous.putIfAbsent(fqn, module)
            }
        }
        return ClasspathIndex(provided, ambiguous, providedQualified)
    }

    /**
     * Scope levels order lifetimes across the whole app, so a level claimed by two
     * differently-named scopes in *different* modules is as fatal as the local case
     * (which GraphValidator already reports).
     */
    private fun crossModuleScopeCollisions(scan: ScanResult, classpath: ClasspathIndex): List<Issue> {
        val local = scan.scopes.distinctBy { it.name to it.level }
        val remote = classpath.provided.values.mapNotNull { it.scope }
            .distinctBy { it.name to it.level }
            .filter { r -> local.none { it.name == r.name && it.level == r.level } }
        return (local + remote)
            .groupBy { it.level }
            .filterValues { defs ->
                defs.map { it.name }.distinct().size > 1 &&
                    !defs.all { d -> local.any { it.name == d.name && it.level == d.level } }
            }
            .map { (level, defs) ->
                Issue(
                    Severity.ERROR,
                    "Scope level collision across modules: ${defs.joinToString(" and ") { it.name }} all declare " +
                        "level $level — levels order lifetimes app-wide, so each scope needs its own.",
                )
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
