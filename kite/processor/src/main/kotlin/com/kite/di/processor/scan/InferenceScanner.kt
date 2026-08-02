package com.kite.di.processor.scan

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind
import com.kite.di.processor.ProcessorOptions
import com.kite.di.processor.codegen.RuntimeNames
import com.kite.di.processor.export.DecisionsExporter
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.ClasspathIndex
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.GraphArg
import com.kite.di.processor.model.InferredBy
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.SetBindingModel
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.kite.di.processor.model.ViewModelModel
import com.kite.di.processor.model.ViewModelParam
import com.kite.di.processor.model.setKeyOf
import com.kite.di.processor.validate.GraphValidator
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility

private const val SET_FQN = "kotlin.collections.Set"
private const val KOTLIN_LAZY_FQN = "kotlin.Lazy"
private const val FUNCTION0_FQN = "kotlin.Function0"
private const val VIEWMODEL_FQN = "androidx.lifecycle.ViewModel"
private const val SAVED_STATE_FQN = "androidx.lifecycle.SavedStateHandle"

/** Namespaces that are never "project" types — you can only bind what you own. */
private val FOREIGN_PREFIXES = listOf(
    "kotlin.", "kotlinx.", "java.", "javax.",
    "android.", "androidx.", "com.google.", "org.jetbrains.", "org.intellij.",
)

/** Framework base classes whose subclasses are never bindings. */
private val FRAMEWORK_BASES = setOf(
    "android.app.Activity",
    "android.app.Application",
    "android.app.Service",
    "android.content.BroadcastReceiver",
    "android.content.ContentProvider",
    "androidx.fragment.app.Fragment",
)

/**
 * The inference scanner: reads every class declaration of the
 * compilation and infers the graph from what the code already says — no
 * annotations. Rules R1–R5:
 *
 *  R1 implementation  — concrete class implementing a project interface/abstract class
 *  R2 view model      — ViewModel subclasses become entry points with generated adapters
 *  R3 root            — `@Root` decisions, for runtime-only resolution sites
 *  R4 closure         — constructor parameters pull project classes in, recursively
 *  R5 leaf            — unprovidable parameters bubble up as graph arguments
 */
class InferenceScanner(
    private val resolver: Resolver,
    private val options: ProcessorOptions,
    private val rules: RuleSet,
    /** Keys other source modules' registries provide (from `@ProvidedKeys`). */
    private val classpath: ClasspathIndex = ClasspathIndex.EMPTY,
) {

    private val issues = mutableListOf<Issue>()
    private val decisions = mutableListOf<com.kite.di.graph.PendingDecision>()

    // ---- catalog ----------------------------------------------------------------

    private val allClasses: List<KSClassDeclaration> by lazy {
        val out = mutableListOf<KSClassDeclaration>()
        fun collect(declarations: Sequence<KSDeclaration>) {
            for (declaration in declarations) {
                if (declaration is KSClassDeclaration) {
                    out += declaration
                    collect(declaration.declarations)
                }
            }
        }
        resolver.getAllFiles().forEach { collect(it.declarations) }
        out
    }

    private val superFqnsCache = mutableMapOf<KSClassDeclaration, Set<String>>()

    private fun superFqns(cls: KSClassDeclaration): Set<String> = superFqnsCache.getOrPut(cls) {
        cls.getAllSuperTypes().mapNotNull { it.declaration.qualifiedName?.asString() }.toSet()
    }

    private fun isProjectFqn(fqn: String): Boolean = FOREIGN_PREFIXES.none { fqn.startsWith(it) }

    private fun isViewModel(cls: KSClassDeclaration): Boolean = VIEWMODEL_FQN in superFqns(cls)

    private fun isFrameworkClass(cls: KSClassDeclaration): Boolean =
        FRAMEWORK_BASES.any { it in superFqns(cls) }

    /** Concrete, constructible, non-framework — a binding candidate. */
    private fun isBindable(cls: KSClassDeclaration): Boolean =
        cls.classKind == ClassKind.CLASS &&
            !cls.isAbstract() &&
            cls.getVisibility() != Visibility.PRIVATE &&
            cls.typeParameters.isEmpty() &&
            !isViewModel(cls) &&
            !isFrameworkClass(cls) &&
            Modifier.DATA !in cls.modifiers &&
            Modifier.VALUE !in cls.modifiers &&
            cls.qualifiedName != null

    /** Interfaces and abstract classes this class can be bound to (project-owned only). */
    private fun bindableSupertypes(cls: KSClassDeclaration): List<KSClassDeclaration> =
        cls.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .filter { superDecl ->
                val fqn = superDecl.qualifiedName?.asString() ?: return@filter false
                isProjectFqn(fqn) &&
                    (superDecl.classKind == ClassKind.INTERFACE ||
                        (superDecl.classKind == ClassKind.CLASS && superDecl.isAbstract()))
            }
            .toList()

    // ---- scan -------------------------------------------------------------------

    fun scan(): ScanResult {
        val concrete = allClasses.filter(::isBindable).associateBy { it.qualifiedName!!.asString() }
        val viewModelClasses = allClasses.filter {
            it.classKind == ClassKind.CLASS && !it.isAbstract() &&
                it.getVisibility() != Visibility.PRIVATE && isViewModel(it)
        }

        // supertype fqn → implementations in this compilation
        val implIndex = mutableMapOf<String, MutableList<KSClassDeclaration>>()
        val supertypeRefs = mutableMapOf<String, TypeRef>()
        for (cls in concrete.values) {
            for (superDecl in bindableSupertypes(cls)) {
                val fqn = superDecl.qualifiedName!!.asString()
                implIndex.getOrPut(fqn) { mutableListOf() } += cls
                supertypeRefs[fqn] = typeRef(superDecl)
            }
        }

        // ---- seeds: R1 implementations + R3 roots --------------------------------
        val included = LinkedHashMap<String, InferredBy>()
        for ((fqn, cls) in concrete) {
            if (bindableSupertypes(cls).isNotEmpty()) included[fqn] = InferredBy.IMPLEMENTATION
        }
        for (root in rules.roots) {
            val cls = concrete[root.fqn]
            if (cls == null) {
                val vm = viewModelClasses.firstOrNull { it.qualifiedName?.asString() == root.fqn }
                issues += Issue(
                    Severity.ERROR,
                    if (vm != null) {
                        "${root.where} — @Root(${vm.simpleName.asString()}::class): ViewModels are entry points already " +
                            "(a `${vm.simpleName.asString().replaceFirstChar(Char::lowercaseChar)}()` adapter is generated) — remove the rule."
                    } else {
                        "${root.where} — @Root(${root.fqn}::class): not a concrete injectable class of this module — " +
                            "abstract, private, data and framework classes (and other modules' classes) cannot be roots."
                    },
                )
                continue
            }
            included.putIfAbsent(root.fqn, InferredBy.ROOT_RULE)
        }

        // ---- closure: R4/R5 — walk constructors, pull project classes, bubble leaves
        val dependenciesOf = mutableMapOf<String, List<DependencyModel>>()
        val graphArgs = mutableMapOf<String, GraphArg>() // by arg name
        val setDemand = mutableMapOf<String, Provenance>() // element iface fqn → first consumer site
        val singularDemand = mutableMapOf<String, MutableList<Pair<String, Provenance>>>() // iface fqn → consumers

        fun bubble(name: String, type: TypeRef, site: Provenance): Key {
            val existing = graphArgs[name]
            if (existing != null && existing.type.fqn != type.fqn) {
                issues += Issue(
                    Severity.ERROR,
                    "Graph argument '$name' declared with two types:\n" +
                        "  ${existing.type.displayName} (${existing.sites.first().filePath}:${existing.sites.first().line})\n" +
                        "  ${type.displayName} (${site.filePath}:${site.line})\n" +
                        "  hint: same name means same argument — rename one parameter.",
                )
            }
            graphArgs[name] = GraphArg(name, type, (existing?.sites ?: emptyList()) + site)
            return Key(type.fqn, qualifier = name)
        }

        val worklist = ArrayDeque(included.keys)
        while (worklist.isNotEmpty()) {
            val fqn = worklist.removeFirst()
            if (fqn in dependenciesOf) continue
            val cls = concrete.getValue(fqn)
            val display = cls.simpleName.asString()
            val constructor = selectConstructor(cls)
            if (constructor == null) {
                dependenciesOf[fqn] = emptyList() // error already reported
                continue
            }

            dependenciesOf[fqn] = constructor.parameters.mapNotNull { parameter ->
                resolveParam(parameter, display, concrete, implIndex, supertypeRefs, setDemand, singularDemand) { name, type, site ->
                    bubble(name, type, site)
                }?.also { dep ->
                    // R4: a project concrete class used as a dependency joins the graph.
                    val depFqn = dep.type.fqn
                    if (!dep.isGraphArg && dep.setElement == null && depFqn in concrete && depFqn !in dependenciesOf && included.putIfAbsent(depFqn, InferredBy.CLOSURE) == null) {
                        worklist += depFqn
                    }
                }
            }
        }

        // ---- R2: ViewModels -------------------------------------------------------
        val viewModels = viewModelClasses.mapNotNull { vm ->
            scanViewModel(vm, concrete, implIndex, supertypeRefs, setDemand, singularDemand) { depFqn ->
                if (depFqn !in dependenciesOf && included.putIfAbsent(depFqn, InferredBy.CLOSURE) == null) {
                    // late closure additions from ViewModel parameters
                    val cls = concrete.getValue(depFqn)
                    val ctor = selectConstructor(cls)
                    dependenciesOf[depFqn] = ctor?.parameters?.mapNotNull { p ->
                        resolveParam(p, cls.simpleName.asString(), concrete, implIndex, supertypeRefs, setDemand, singularDemand) { name, type, site ->
                            bubble(name, type, site)
                        }
                    } ?: emptyList()
                }
            }
        }
        // ViewModel closure can pull classes whose own dependencies are unscanned yet.
        while (worklist.isNotEmpty() || included.keys.any { it !in dependenciesOf }) {
            val missing = included.keys.firstOrNull { it !in dependenciesOf } ?: break
            val cls = concrete.getValue(missing)
            val ctor = selectConstructor(cls)
            dependenciesOf[missing] = ctor?.parameters?.mapNotNull { p ->
                resolveParam(p, cls.simpleName.asString(), concrete, implIndex, supertypeRefs, setDemand, singularDemand) { name, type, site ->
                    bubble(name, type, site)
                }?.also { dep ->
                    val depFqn = dep.type.fqn
                    if (!dep.isGraphArg && dep.setElement == null && depFqn in concrete) {
                        included.putIfAbsent(depFqn, InferredBy.CLOSURE)
                    }
                }
            } ?: emptyList()
        }

        // ---- interface binding assignment (sole implementation or `bind` rule) -----
        val chosen = mutableMapOf<String, String>() // iface fqn → impl fqn
        val ambiguousInterfaces = mutableListOf<TypeRef>()
        for (bind in rules.binds) {
            val impls = implIndex[bind.interfaceFqn]
            when {
                impls == null -> {
                    val owner = classpath.ambiguous[bind.interfaceFqn]
                        ?: classpath.provided[bind.interfaceFqn]?.module
                    issues += Issue(
                        Severity.ERROR,
                        "${bind.where} — @Bind(${bind.interfaceFqn}::class, …): no implementations of that type in this module." +
                            (owner?.let { " The implementations live in $it — move this @Bind to $it's GraphRules.kt." } ?: ""),
                    )
                }
                impls.none { it.qualifiedName?.asString() == bind.implFqn } -> issues += Issue(
                    Severity.ERROR,
                    "${bind.where} — @Bind(…, to = ${bind.implFqn}::class): ${bind.implFqn.substringAfterLast('.')} " +
                        "does not implement ${bind.interfaceFqn.substringAfterLast('.')}.\n" +
                        "  implementations: ${impls.joinToString { it.simpleName.asString() }}",
                )
                else -> chosen[bind.interfaceFqn] = bind.implFqn
            }
        }
        val extraKeysOf = mutableMapOf<String, MutableList<Pair<Key, TypeRef>>>()
        for ((ifaceFqn, impls) in implIndex) {
            val implFqn = when {
                impls.size == 1 -> impls.single().qualifiedName!!.asString()
                else -> chosen[ifaceFqn] // multiple implementations need a rule
            }
            if (implFqn == null) {
                // Exported via @ProvidedKeys(ambiguous = …) even when nothing local
                // consumes the interface: a downstream module's consumer must get
                // "add @Bind to this module's rules", not a silent graph argument.
                ambiguousInterfaces += supertypeRefs.getValue(ifaceFqn)
                // Error only if someone singularly consumes the interface.
                val consumers = singularDemand[ifaceFqn].orEmpty()
                if (consumers.isNotEmpty()) {
                    // The same ambiguity, structured: one decision card per interface,
                    // a button per implementation (decisions.json).
                    decisions += DecisionsExporter.bindAmbiguity(
                        subjectFqn = ifaceFqn,
                        subjectDisplay = supertypeRefs.getValue(ifaceFqn).displayName,
                        consumers = consumers.map { (consumer, site) -> "$consumer (${site.filePath}:${site.line})" },
                        candidates = impls.map { impl ->
                            DecisionsExporter.Candidate(
                                fqn = impl.qualifiedName!!.asString(),
                                displayName = impl.simpleName.asString(),
                                site = provenance(impl),
                            )
                        },
                    )
                }
                for ((consumer, site) in consumers) {
                    issues += Issue(
                        Severity.ERROR,
                        buildString {
                            appendLine("${supertypeRefs.getValue(ifaceFqn).displayName} has ${impls.size} implementations:")
                            impls.forEachIndexed { i, impl ->
                                val p = provenance(impl)
                                appendLine("  ${i + 1}) ${impl.simpleName.asString()} (${p.filePath}:${p.line})")
                            }
                            appendLine("consumed as a single ${supertypeRefs.getValue(ifaceFqn).displayName} by $consumer (${site.filePath}:${site.line})")
                            appendLine("  hint: add to ${rules.holderFileName}:  ${DecisionsExporter.bindInsert(ifaceFqn, impls.first().qualifiedName!!.asString())}")
                            append("  (the dependency board shows this as a clickable decision card)")
                        },
                    )
                }
                continue
            }
            if (implFqn in included) {
                // Two modules must not bind the same interface — the merged registry
                // would carry two records for one key.
                val elsewhere = classpath.provided[ifaceFqn]
                if (elsewhere != null) {
                    val impl = concrete.getValue(implFqn)
                    val p = provenance(impl)
                    issues += Issue(
                        Severity.ERROR,
                        "${supertypeRefs.getValue(ifaceFqn).displayName} is implemented by ${impl.simpleName.asString()} " +
                            "(${p.filePath}:${p.line}) but is already bound in ${elsewhere.module}.\n" +
                            "  hint: keep one implementation per graph — remove one of them, " +
                            "or introduce a narrower interface for this module's variant.",
                    )
                    continue
                }
                extraKeysOf.getOrPut(implFqn) { mutableListOf() } += Key(ifaceFqn) to supertypeRefs.getValue(ifaceFqn)
            }
        }

        // ---- scopes ----------------------------------------------------------------
        val scopeRuleByFqn = mutableMapOf<String, ScopeDef?>()
        for (rule in rules.scopes) {
            if (rule.fqn !in included) {
                issues += Issue(
                    Severity.ERROR,
                    if (viewModelClasses.any { it.qualifiedName?.asString() == rule.fqn }) {
                        "${rule.where} — @Scoped(${rule.fqn.substringAfterLast('.')}::class): ViewModels are retained by the " +
                            "ViewModelStore (rotation survival, onCleared) — a graph scope would double-cache. Remove the rule."
                    } else if (rule.fqn in classpath.provided) {
                        "${rule.where} — @Scoped(${rule.fqn.substringAfterLast('.')}::class): that class belongs to " +
                            "${classpath.provided.getValue(rule.fqn).module} — decisions live with the module that " +
                            "owns the class; move the rule to its GraphRules.kt."
                    } else {
                        "${rule.where} — @Scoped(${rule.fqn}::class): that class is not in the inferred graph " +
                            "(not an implementation, root, or dependency)."
                    },
                )
                continue
            }
            scopeRuleByFqn[rule.fqn] = rule.scope
        }
        val customScopes = rules.scopes.mapNotNull { it.scope }
            .filter { def -> ScanResult.BUILT_IN_SCOPES.none { it.name == def.name } }
            .distinctBy { it.name }

        // ---- assemble ----------------------------------------------------------------
        val bindings = included.map { (fqn, inferredBy) ->
            val cls = concrete.getValue(fqn)
            val extras = extraKeysOf[fqn].orEmpty()
            val scope = when {
                fqn in scopeRuleByFqn -> scopeRuleByFqn[fqn] // explicit rule (null = unscoped)
                extras.isNotEmpty() -> ScopeDef("Singleton", 0) // implementations default to singleton
                else -> null // closure/root classes default to unscoped
            }
            BindingModel(
                key = Key(fqn),
                keyType = typeRef(cls),
                extraKeys = extras.map { it.first },
                extraKeyTypes = extras.map { it.second },
                scopeLevel = scope?.level,
                scopeName = scope?.name,
                declaration = cls.simpleName.asString(),
                inferredBy = inferredBy,
                provenance = provenance(cls),
                dependencies = dependenciesOf[fqn].orEmpty(),
                suppressions = suppressionsOf(cls) +
                    // a root exists for runtime-only resolution — "unused" is its point
                    (if (inferredBy == InferredBy.ROOT_RULE) setOf(GraphValidator.SUPPRESS_UNUSED) else emptySet()),
                targetType = typeRef(cls),
            )
        }

        val setBindings = setDemand.map { (elementFqn, site) ->
            val impls = implIndex[elementFqn].orEmpty().sortedBy { it.qualifiedName!!.asString() }
            SetBindingModel(
                key = setKeyOf(elementFqn),
                elementType = supertypeRefs.getValue(elementFqn),
                elementKeys = impls.map { Key(it.qualifiedName!!.asString()) },
                elementTypes = impls.map { typeRef(it) },
                provenance = site,
            )
        }

        return ScanResult(
            bindings = bindings.sortedBy { it.key.id },
            setBindings = setBindings.sortedBy { it.key.id },
            viewModels = viewModels.sortedBy { it.targetType.fqn },
            graphArgs = graphArgs.values.sortedBy { it.name },
            scopes = (ScanResult.BUILT_IN_SCOPES + customScopes).sortedBy { it.level },
            classpathKeys = classpath.provided.entries.associate { (fqn, binding) -> Key(fqn) to binding.scope },
            ambiguousInterfaces = ambiguousInterfaces.sortedBy { it.fqn },
            issues = issues,
            decisions = decisions.sortedBy { it.id },
        )
    }

    // ---- per-declaration helpers ---------------------------------------------------

    private fun selectConstructor(cls: KSClassDeclaration): KSFunctionDeclaration? {
        cls.primaryConstructor?.let { primary ->
            if (primary.getVisibility() == Visibility.PRIVATE) {
                val where = provenance(cls)
                issues += Issue(
                    Severity.ERROR,
                    "${cls.simpleName.asString()} (${where.filePath}:${where.line}) is in the graph but its " +
                        "primary constructor is private — make it non-private, or remove the class from the graph.",
                )
                return null
            }
            return primary
        }
        val constructors = cls.getConstructors().filter { it.getVisibility() != Visibility.PRIVATE }.toList()
        if (constructors.size == 1) return constructors.single()
        val where = provenance(cls)
        issues += Issue(
            Severity.ERROR,
            "${cls.simpleName.asString()} (${where.filePath}:${where.line}) has ${constructors.size} constructors " +
                "and no primary one — the graph is inferred from the primary constructor.\n" +
                "  hint: declare a primary constructor.",
        )
        return null
    }

    /** One constructor parameter → a dependency edge, set demand, or a bubbled graph argument. */
    private fun resolveParam(
        parameter: KSValueParameter,
        ownerDisplay: String,
        concrete: Map<String, KSClassDeclaration>,
        implIndex: Map<String, List<KSClassDeclaration>>,
        supertypeRefs: MutableMap<String, TypeRef>,
        setDemand: MutableMap<String, Provenance>,
        singularDemand: MutableMap<String, MutableList<Pair<String, Provenance>>>,
        bubble: (name: String, type: TypeRef, site: Provenance) -> Key,
    ): DependencyModel? {
        val site = provenance(parameter)
        val paramName = parameter.name?.asString() ?: "arg"
        var actual = parameter.type.resolve()
        var deferred = DeferredKind.NONE

        when (actual.declaration.qualifiedName?.asString()) {
            RuntimeNames.PROVIDER_FQN, FUNCTION0_FQN -> deferred = DeferredKind.PROVIDER
            RuntimeNames.LAZY_FQN, KOTLIN_LAZY_FQN -> deferred = DeferredKind.LAZY
            else -> {
                val fqn = actual.declaration.qualifiedName?.asString()
                if (fqn != null && isUnsupportedFunctionShape(fqn)) {
                    issues += Issue(
                        Severity.ERROR,
                        "$ownerDisplay (${site.filePath}:${site.line}): only () -> T function types can be " +
                            "injected (deferred lookup) — function types with parameters or suspend functions " +
                            "have no binding identity.\n" +
                            "  hint: inject the pieces and build the function where it is used, or declare a small interface.",
                    )
                    return null
                }
            }
        }
        if (deferred != DeferredKind.NONE) {
            actual = actual.arguments.firstOrNull()?.type?.resolve() ?: run {
                issues += Issue(
                    Severity.ERROR,
                    "$ownerDisplay (${site.filePath}:${site.line}): could not resolve the type argument of " +
                        "${actual.declaration.simpleName.asString()}<...>.",
                )
                return null
            }
        }

        val declaration = actual.declaration
        val fqn = declaration.qualifiedName?.asString() ?: run {
            issues += Issue(
                Severity.ERROR,
                "$ownerDisplay (${site.filePath}:${site.line}): could not resolve a dependency type (missing from the classpath?).",
            )
            return null
        }

        // Set<I> → inferred multibinding over every implementation of I.
        if (fqn == SET_FQN) {
            val element = actual.arguments.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration
            val elementFqn = element?.qualifiedName?.asString()
            if (element == null || elementFqn == null) {
                issues += Issue(Severity.ERROR, "$ownerDisplay (${site.filePath}:${site.line}): could not resolve the element type of Set<...>.")
                return null
            }
            val impls = implIndex[elementFqn].orEmpty()
            if (impls.isEmpty()) {
                issues += Issue(
                    Severity.ERROR,
                    "Set<${element.simpleName.asString()}> injected by $ownerDisplay (${site.filePath}:${site.line}) has no implementations.\n" +
                        "  hint: implement ${element.simpleName.asString()} in a project class, or remove the parameter.",
                )
                return null
            }
            supertypeRefs.putIfAbsent(elementFqn, typeRef(element))
            setDemand.putIfAbsent(elementFqn, site)
            return DependencyModel(
                key = setKeyOf(elementFqn),
                type = typeRef(element),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                deferred = deferred,
                paramName = paramName,
                optional = parameter.hasDefault,
                setElement = typeRef(element),
                site = site,
            )
        }

        // Reject other parameterized shapes — they have no Class-reference key.
        if (actual.arguments.isNotEmpty()) {
            issues += Issue(
                Severity.ERROR,
                "$ownerDisplay (${site.filePath}:${site.line}): parameter '$paramName' has a parameterized type " +
                    "(${declaration.simpleName.asString()}<…>) — parameterized types have no binding identity.\n" +
                    "  hint: wrap it in a small class and depend on that.",
            )
            return null
        }

        fun plainDep(key: Key) = DependencyModel(
            key = key,
            type = typeRef(declaration),
            siteKind = SiteKind.CONSTRUCTOR_PARAM,
            deferred = deferred,
            paramName = paramName,
            optional = parameter.hasDefault,
            site = site,
        )

        return when {
            // R4 target: project concrete class in this compilation.
            fqn in concrete -> plainDep(Key(fqn))
            // Interface / abstract class with implementations here: bind (sole or by rule).
            fqn in implIndex -> {
                singularDemand.getOrPut(fqn) { mutableListOf() } += "$ownerDisplay, constructor param '$paramName'" to site
                plainDep(Key(fqn))
            }
            // Cross-module edge: another source module's registry provides this key
            // (its implementation, root, or closure — read from @ProvidedKeys).
            fqn in classpath.provided -> plainDep(Key(fqn))
            // Provided nowhere because the owning module never chose: ambiguity is
            // a decision, and decisions live in the module that owns the interface.
            fqn in classpath.ambiguous -> {
                val module = classpath.ambiguous.getValue(fqn)
                issues += Issue(
                    Severity.ERROR,
                    "${fqn.substringAfterLast('.')} has multiple implementations in $module, consumed as a " +
                        "single ${fqn.substringAfterLast('.')} by $ownerDisplay (${site.filePath}:${site.line})\n" +
                        "  hint: add a @Bind(${fqn.substringAfterLast('.')}::class, to = …::class) decision " +
                        "to $module's GraphRules.kt — the choice belongs to the module that owns the implementations.",
                )
                null
            }
            // Built-ins the runtime always provides.
            Key(fqn) in com.kite.di.processor.model.BUILT_IN_KEYS -> plainDep(Key(fqn))
            // Optional leaves never bubble: the Kotlin default is the fallback.
            parameter.hasDefault -> plainDep(Key(fqn))
            // R5: a leaf — bubbles up as a graph argument keyed by the parameter name.
            else -> DependencyModel(
                key = bubble(paramName, typeRef(declaration), site),
                type = typeRef(declaration),
                siteKind = SiteKind.CONSTRUCTOR_PARAM,
                deferred = deferred,
                paramName = paramName,
                optional = false,
                isGraphArg = true,
                site = site,
            )
        }
    }

    private fun scanViewModel(
        vm: KSClassDeclaration,
        concrete: Map<String, KSClassDeclaration>,
        implIndex: Map<String, List<KSClassDeclaration>>,
        supertypeRefs: MutableMap<String, TypeRef>,
        setDemand: MutableMap<String, Provenance>,
        singularDemand: MutableMap<String, MutableList<Pair<String, Provenance>>>,
        onClosureDependency: (String) -> Unit,
    ): ViewModelModel? {
        val where = provenance(vm)
        val display = vm.simpleName.asString()
        val constructor = selectConstructor(vm) ?: return null

        val params = constructor.parameters.mapNotNull { parameter ->
            val name = parameter.name?.asString() ?: "arg"
            val resolved = parameter.type.resolve()
            val fqn = resolved.declaration.qualifiedName?.asString()
            if (fqn == SAVED_STATE_FQN) return@mapNotNull ViewModelParam.SavedState(name)

            // Runtime parameters of a ViewModel are adapter parameters, not graph arguments.
            val dep = resolveParam(
                parameter, display, concrete, implIndex, supertypeRefs, setDemand, singularDemand,
            ) { argName, type, _ -> Key(type.fqn, qualifier = argName) } ?: return@mapNotNull null

            if (dep.isGraphArg) {
                ViewModelParam.Runtime(name, dep.type)
            } else {
                if (dep.setElement == null && dep.type.fqn in concrete) onClosureDependency(dep.type.fqn)
                ViewModelParam.Injected(name, dep)
            }
        }
        if (params.size != constructor.parameters.size) return null // errors already reported
        return ViewModelModel(targetType = typeRef(vm), params = params, provenance = where)
    }

    // ---- shared helpers --------------------------------------------------------------

    /** `(A) -> B`, `suspend () -> T`, … — everything function-shaped except plain `() -> T`. */
    private fun isUnsupportedFunctionShape(fqn: String): Boolean =
        fqn != FUNCTION0_FQN &&
            (fqn.startsWith("kotlin.Function") || fqn.startsWith("kotlin.coroutines.SuspendFunction"))

    private fun suppressionsOf(symbol: KSAnnotated): Set<String> =
        symbol.annotations.filter { it.shortName.asString() == "Suppress" }
            .flatMap { annotation ->
                annotation.arguments.flatMap { arg ->
                    (arg.value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                }
            }.toSet()

    private fun typeRef(declaration: KSDeclaration): TypeRef {
        val pkg = declaration.packageName.asString()
        val qualified = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        val simple = qualified.removePrefix(pkg).trimStart('.')
        return TypeRef(pkg, simple.split('.'))
    }

    private fun provenance(node: KSNode): Provenance {
        val location = node.location as? FileLocation
            ?: return Provenance(options.moduleName, "<unknown>", 0)
        val path = location.filePath
            .removePrefix(options.rootDir).trimStart('/', '\\')
        return Provenance(options.moduleName, path, location.lineNumber)
    }
}
