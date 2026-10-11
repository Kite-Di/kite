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

/** Platform annotations and Kite's own decision vocabulary never act as qualifiers, owned or not. */
private val NON_QUALIFIER_PACKAGES = listOf(
    "kotlin.", "java.", "javax.", "jakarta.", "androidx.", "android.",
    "org.jetbrains.", "com.kite.di.",
)
private const val KOTLIN_LAZY_FQN = "kotlin.Lazy"
private const val FUNCTION0_FQN = "kotlin.Function0"
private const val VIEWMODEL_FQN = "androidx.lifecycle.ViewModel"
private const val SAVED_STATE_FQN = "androidx.lifecycle.SavedStateHandle"

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

    /** Set element fqn → its first consumer in this compilation (display, site), for the empty-set check. */
    private val setConsumers = linkedMapOf<String, Pair<String, Provenance>>()

    /** Every mark [qualifierOf] returned, by FQN → its type (see [ScanResult.marks]). */
    private val marks = LinkedHashMap<String, TypeRef>()


    private val issues = mutableListOf<Issue>()
    private val decisions = mutableListOf<com.kite.di.graph.PendingDecision>()

    /**
     * Gradle paths of the modules this one resolved a key from. Exported via
     * `@ProvidedKeys.requires`, because only the application can tell whether each of
     * them also reached *its* compile classpath — `implementation` is not transitive,
     * and a module that did not reach it is missing from the merge.
     */
    private val requiredModules = linkedSetOf<String>()

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

    /** The packages this compilation declares — its own types are ours by definition. */
    private val sourcePackages: Set<String> by lazy {
        resolver.getAllFiles().mapTo(mutableSetOf()) { it.packageName.asString() }
    }

    /**
     * Is this declaration the project's own — a candidate to be bound to — or a
     * library's? Answered from this compilation's own packages and from the build's
     * classpath, never from the name: see [OwnedPackages].
     *
     * Not `containingFile != null`: KSP hands classpath declarations a synthetic file
     * too (`ViewBinding.class`), so every library interface would look local.
     */
    private fun isProjectType(decl: KSClassDeclaration): Boolean {
        val packageName = decl.packageName.asString()
        return packageName in sourcePackages || options.ownedPackages.owns(packageName)
    }

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
                superDecl.qualifiedName ?: return@filter false
                isProjectType(superDecl) &&
                    (superDecl.classKind == ClassKind.INTERFACE ||
                        (superDecl.classKind == ClassKind.CLASS && superDecl.isAbstract()))
            }
            .toList()

    /** The `T` of the `Supplies<T>` this class implements, directly or through a base. */
    private fun suppliesArgument(cls: KSClassDeclaration): KSType? =
        cls.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == RuntimeNames.SUPPLIES_FQN }
            ?.arguments?.firstOrNull()?.type?.resolve()

    /**
     * R1 for types the project does not own: implementing `Supplies<T>` binds `T`
     * (ADR 13). Returns supplied fqn → the classes in this module that supply it —
     * several is an ambiguity, resolved by the same `@Bind` path as two
     * implementations of one interface.
     *
     * Every shape that cannot become a key is reported here rather than left to
     * disappear: a supplier that is not a binding candidate would otherwise be
     * silently not a supplier, and the type it meant to provide would bubble up to
     * `Graph.start` — the very leak the feature removes.
     */
    private fun scanSuppliers(
        concrete: Map<String, KSClassDeclaration>,
        supertypeRefs: MutableMap<String, TypeRef>,
    ): Map<String, MutableList<KSClassDeclaration>> {
        val suppliers = mutableMapOf<String, MutableList<KSClassDeclaration>>()
        for (cls in allClasses) {
            val argument = suppliesArgument(cls) ?: continue
            // An abstract class or interface extending Supplies is a base for
            // suppliers, not one itself — its concrete subclasses are found above.
            if (cls.classKind != ClassKind.CLASS || cls.isAbstract()) continue
            val fqn = cls.qualifiedName?.asString() ?: continue
            val display = cls.simpleName.asString()
            val at = provenance(cls)
            val where = "$display (${at.filePath}:${at.line})"

            fun reject(reason: String, hint: String) {
                issues += Issue(Severity.ERROR, "$where supplies a type but $reason\n  hint: $hint")
            }

            when {
                cls.typeParameters.isNotEmpty() -> reject(
                    "is generic — what it supplies would depend on the type argument, and a key is one class.",
                    "give each supplied type its own class.",
                )
                cls.getVisibility() == Visibility.PRIVATE -> reject(
                    "is private — the generated registry is a separate file and cannot name it.",
                    "make it internal or public.",
                )
                isViewModel(cls) -> reject(
                    "is a ViewModel — androidx owns those instances, so a ViewModel is an entry point, never a binding.",
                    "move the construction into an ordinary class and supply from there.",
                )
                isFrameworkClass(cls) -> reject(
                    "is an Android framework class — the platform constructs those, not the graph.",
                    "supply from an ordinary class.",
                )
                Modifier.DATA in cls.modifiers || Modifier.VALUE in cls.modifiers -> reject(
                    "is a ${if (Modifier.DATA in cls.modifiers) "data" else "value"} class — those carry values, not bindings.",
                    "use an ordinary class.",
                )
                fqn !in concrete -> reject(
                    "is not a binding candidate.",
                    "a supplier is an ordinary class Kite can construct.",
                )
                else -> validateSuppliedType(argument, where, concrete)?.let { supplied ->
                    val suppliedFqn = supplied.qualifiedName!!.asString()
                    supertypeRefs[suppliedFqn] = typeRef(supplied)
                    suppliers.getOrPut(suppliedFqn) { mutableListOf() } += cls
                }
            }
        }
        return suppliers
    }

    /** The supplied type must be a plain class reference the project does not own. */
    private fun validateSuppliedType(
        argument: KSType,
        where: String,
        concrete: Map<String, KSClassDeclaration>,
    ): KSClassDeclaration? {
        fun reject(reason: String, hint: String): KSClassDeclaration? {
            issues += Issue(Severity.ERROR, "$where supplies $reason\n  hint: $hint")
            return null
        }
        if (argument.isError) return reject(
            "a type that does not resolve.",
            "check the import, or add the library to this module's dependencies.",
        )
        val declaration = argument.declaration as? KSClassDeclaration ?: return reject(
            "a type parameter (${argument.declaration.simpleName.asString()}) — a key is one class.",
            "supply a concrete type.",
        )
        val fqn = declaration.qualifiedName?.asString() ?: return reject(
            "a type with no qualified name.",
            "supply a named class.",
        )
        val display = declaration.simpleName.asString()
        if (argument.arguments.isNotEmpty()) return reject(
            "$display<…> — a parameterized type has no Class-reference key.",
            "wrap it in a small class and supply that.",
        )
        if (argument.isMarkedNullable) return reject(
            "$display? — a binding is never null.",
            "supply $display, and let the consumer decide what absence means.",
        )
        if (isViewModel(declaration)) return reject(
            "$display, a ViewModel — those are entry points owned by androidx, not bindings.",
            "inject what the ViewModel needs instead.",
        )
        if (Key(fqn) in com.kite.di.processor.model.BUILT_IN_KEYS) return reject(
            "$display, which the runtime already provides.",
            "take $display as an ordinary constructor parameter.",
        )
        if (fqn in concrete) return reject(
            "$display, a class of this project — Kite constructs it already, so supplying it would claim a key it owns.",
            "delete the supplier; add @Root($display::class) to GraphRules.kt if nothing constructs it.",
        )
        return declaration
    }

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

        // Types this module supplies on behalf of code it does not own (ADR 13).
        // Indexed as implementations of the supplied type: consuming one, choosing
        // between two with `@Bind`, the ambiguity error and its board decision card
        // then all take the path interface implementations already take.
        val suppliers = scanSuppliers(concrete, supertypeRefs)
        for ((suppliedFqn, classes) in suppliers) {
            implIndex.getOrPut(suppliedFqn) { mutableListOf() } += classes
        }

        // Which interfaces something already consumes as a set, anywhere this module
        // can see. An interface in this list is never claimed as a singular binding
        // below: it is a set element, and several modules contributing to one set
        // must not fight over its key.
        val classpathSetDemand = mutableMapOf<String, Provenance>()
        for ((fqn, binding) in classpath.provided) {
            val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn)) ?: continue
            for (parameter in decl.primaryConstructor?.parameters.orEmpty()) {
                val type = parameter.type.resolve()
                if (type.isError || type.declaration.qualifiedName?.asString() != SET_FQN) continue
                val element = type.arguments.firstOrNull()?.type?.resolve()?.declaration
                    as? KSClassDeclaration ?: continue
                val elementFqn = element.qualifiedName?.asString() ?: continue
                supertypeRefs.putIfAbsent(elementFqn, typeRef(element))
                // A binary declaration carries no position; the module's exported
                // provenance is the closest thing to one.
                val at = binding.at
                classpathSetDemand.putIfAbsent(
                    elementFqn,
                    Provenance(
                        binding.module,
                        at?.substringBeforeLast(':') ?: decl.simpleName.asString(),
                        at?.substringAfterLast(':')?.toIntOrNull() ?: 0,
                    ),
                )
            }
        }

        // ---- seeds: R1 implementations + R3 roots --------------------------------
        val included = LinkedHashMap<String, InferredBy>()
        for ((fqn, cls) in concrete) {
            if (bindableSupertypes(cls).isNotEmpty()) included[fqn] = InferredBy.IMPLEMENTATION
        }
        // A supplier is a binding for the same reason: it implements something.
        for (classes in suppliers.values) {
            for (cls in classes) included[cls.qualifiedName!!.asString()] = InferredBy.IMPLEMENTATION
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

        // R4 closure: scans every included class's constructor, and includes the
        // project classes those constructors take. Drained again after the ViewModels,
        // whose parameters add classes nothing else reached.
        val worklist = ArrayDeque(included.keys)
        fun drainClosure() {
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
        }
        drainClosure()

        // ---- R2: ViewModels -------------------------------------------------------
        val viewModels = viewModelClasses.mapNotNull { vm ->
            scanViewModel(vm, concrete, implIndex, supertypeRefs, setDemand, singularDemand) { depFqn ->
                if (depFqn !in dependenciesOf && included.putIfAbsent(depFqn, InferredBy.CLOSURE) == null) {
                    worklist += depFqn
                }
            }
        }
        drainClosure()

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
                        "${bind.where} — @Bind(${bind.interfaceFqn}::class, …): nothing in this module implements " +
                            "or supplies that type." +
                            (owner?.let { " It is provided by $it — move this @Bind to $it's GraphRules.kt." } ?: ""),
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
        /**
         * The same relationship for a type the module does not own: the supplied key
         * is not an extra key on the supplier's record, because the object answering
         * it is the supplier's `value`, not the supplier.
         */
        val suppliedOf = mutableMapOf<String, MutableList<Pair<Key, TypeRef>>>()

        /** Records that [implFqn] answers [key] — as itself, or by supplying it. */
        fun claim(implFqn: String, key: Key, ref: TypeRef) {
            val target = if (key.type in suppliers) suppliedOf else extraKeysOf
            target.getOrPut(implFqn) { mutableListOf() } += key to ref
        }
        /**
         * Implementations a `@Bind` did not pick (or has yet to pick). They stay in
         * the graph under their own class key — the board shows the alternative, and
         * one can still be injected by class — but "never injected" is their normal
         * state, not a smell: the V5 warning would tell you to delete a perfectly
         * good implementation or to paste a `@Root` that describes nothing.
         */
        val unselectedImpls = mutableSetOf<String>()
        for ((ifaceFqn, impls) in implIndex) {
            // Consumed as a set somewhere: these implementations are elements, not
            // competitors for the interface's key. Without this, two modules each
            // holding one contributor would both claim it — which used to surface
            // as a duplicate binding at startup.
            if (ifaceFqn in classpathSetDemand || ifaceFqn in setDemand) continue

            // Implementations carrying a qualifier claim their own key and compete
            // with nobody — several marked implementations of one interface, here or
            // in sibling modules, coexist. What is left unmarked follows the old rule.
            for ((qualifier, marked) in impls.groupBy { qualifierOf(it) }) {
                if (qualifier == null) continue
                if (marked.size > 1) {
                    val p = provenance(marked.first())
                    issues += Issue(
                        Severity.ERROR,
                        "${supertypeRefs.getValue(ifaceFqn).displayName} has ${marked.size} implementations " +
                            "marked @${qualifier.substringAfterLast('.')} " +
                            "(${marked.joinToString { it.simpleName.asString() }}) — a mark selects one " +
                            "implementation, so it cannot be shared (${p.filePath}:${p.line}).",
                    )
                    continue
                }
                val markedFqn = marked.single().qualifiedName!!.asString()
                if (markedFqn in included) {
                    claim(markedFqn, Key(ifaceFqn, qualifier), supertypeRefs.getValue(ifaceFqn))
                }
            }
            val unmarked = impls.filter { qualifierOf(it) == null }
            if (unmarked.isEmpty()) continue

            val implFqn = when {
                unmarked.size == 1 -> unmarked.single().qualifiedName!!.asString()
                else -> chosen[ifaceFqn] // multiple implementations need a rule
            }
            if (unmarked.size > 1) {
                unselectedImpls += unmarked.mapNotNull { it.qualifiedName?.asString() }.filter { it != implFqn }
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
                        candidates = unmarked.map { impl ->
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
                            val display = supertypeRefs.getValue(ifaceFqn).displayName
                            // A supplied type has suppliers, not implementations — the
                            // decision is the same one, but the words have to match the code.
                            appendLine(
                                if (ifaceFqn in suppliers) "$display is supplied by ${unmarked.size} classes:"
                                else "$display has ${unmarked.size} implementations:",
                            )
                            unmarked.forEachIndexed { i, impl ->
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
                    val supplied = ifaceFqn in suppliers
                    issues += Issue(
                        Severity.ERROR,
                        "${supertypeRefs.getValue(ifaceFqn).displayName} is " +
                            (if (supplied) "supplied by " else "implemented by ") +
                            "${impl.simpleName.asString()} (${p.filePath}:${p.line}) but is already bound in " +
                            "${elsewhere.module}.\n" +
                            if (supplied) {
                                "  hint: one supplier per graph — delete this one and depend on " +
                                    "${elsewhere.module}, or supply a type of your own that wraps it."
                            } else {
                                "  hint: keep one implementation per graph — remove one of them, " +
                                    "or introduce a narrower interface for this module's variant."
                            },
                    )
                    continue
                }
                claim(implFqn, Key(ifaceFqn), supertypeRefs.getValue(ifaceFqn))
            }
        }

        // ---- scopes ----------------------------------------------------------------
        val scopeRuleByFqn = mutableMapOf<String, ScopeDef?>()
        for (rule in rules.scopes) {
            if (rule.fqn !in included) {
                issues += Issue(
                    Severity.ERROR,
                    if (viewModelClasses.any { it.qualifiedName?.asString() == rule.fqn }) {
                        "${rule.where} — ${rule.display}: ViewModels are retained by the " +
                            "ViewModelStore (rotation survival, onCleared) — a graph scope would double-cache. Remove the rule."
                    } else if (rule.fqn in suppliers) {
                        "${rule.where} — ${rule.display}: ${rule.fqn.substringAfterLast('.')} is supplied, not " +
                            "constructed — a supplied type lives exactly as long as its supplier, so put the " +
                            "decision on ${suppliers.getValue(rule.fqn).joinToString { it.simpleName.asString() }}."
                    } else if (rule.fqn in implIndex) {
                        "${rule.where} — ${rule.display}: ${rule.fqn.substringAfterLast('.')} is not constructed — " +
                            "a lifetime belongs to the class that is; decide the implementation instead " +
                            "(${implIndex.getValue(rule.fqn).joinToString { it.simpleName.asString() }})."
                    } else if (rule.fqn in classpath.provided) {
                        "${rule.where} — ${rule.display}: that class belongs to " +
                            "${classpath.provided.getValue(rule.fqn).module} — decisions live with the module that " +
                            "owns the class; move the rule to its GraphRules.kt."
                    } else {
                        "${rule.where} — ${rule.display}: ${rule.fqn} is not in the inferred graph " +
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
            val supplied = suppliedOf[fqn].orEmpty()
            val scope = when {
                fqn in scopeRuleByFqn -> scopeRuleByFqn[fqn] // explicit decision (null = @Fresh / "none")
                else -> ScopeDef("Singleton", 0) // everything is a singleton unless decided otherwise (ADR 11)
            }
            BindingModel(
                key = Key(fqn),
                keyType = typeRef(cls),
                extraKeys = extras.map { it.first },
                extraKeyTypes = extras.map { it.second },
                suppliedKeys = supplied.map { it.first },
                suppliedKeyTypes = supplied.map { it.second },
                scopeLevel = scope?.level,
                scopeName = scope?.name,
                declaration = cls.simpleName.asString(),
                inferredBy = inferredBy,
                provenance = provenance(cls),
                dependencies = dependenciesOf[fqn].orEmpty(),
                suppressions = suppressionsOf(cls) +
                    // a root exists for runtime-only resolution — "unused" is its point
                    (if (inferredBy == InferredBy.ROOT_RULE) setOf(GraphValidator.SUPPRESS_UNUSED) else emptySet()) +
                    // an implementation a @Bind did not choose is not dead code
                    (if (fqn in unselectedImpls) setOf(GraphValidator.SUPPRESS_UNUSED) else emptySet()),
                targetType = typeRef(cls),
                internal = cls.getVisibility() == Visibility.INTERNAL,
            )
        }

        // The application is the only compilation that sees every contributor, so it
        // is where a set is finally composed: demands raised in other modules are
        // read off their constructors, and implementations off their supertypes.
        val classpathImpls = mutableMapOf<String, MutableList<KSClassDeclaration>>()
        if (options.aggregate) {
            for ((fqn, _) in classpath.provided) {
                val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn)) ?: continue
                for (superDecl in bindableSupertypes(decl)) {
                    val ifaceFqn = superDecl.qualifiedName?.asString() ?: continue
                    classpathImpls.getOrPut(ifaceFqn) { mutableListOf() } += decl
                    supertypeRefs.putIfAbsent(ifaceFqn, typeRef(superDecl))
                }
            }
        }
        for ((elementFqn, site) in classpathSetDemand) {
            supertypeRefs[elementFqn]?.let { setDemand.putIfAbsent(elementFqn, site) }
        }

        val setBindings = setDemand.mapNotNull { (elementFqn, site) ->
            val impls = (implIndex[elementFqn].orEmpty() + classpathImpls[elementFqn].orEmpty())
                .distinctBy { it.qualifiedName!!.asString() }
                .sortedBy { it.qualifiedName!!.asString() }
            val consumer = setConsumers[elementFqn]
            if (impls.isEmpty() && options.aggregate && consumer != null) {
                val (ownerDisplay, at) = consumer
                val element = supertypeRefs.getValue(elementFqn).displayName
                issues += Issue(
                    Severity.ERROR,
                    "Set<$element> injected by $ownerDisplay (${at.filePath}:${at.line}) has no implementations.\n" +
                        "  hint: implement $element in a project class, or remove the parameter.",
                )
                return@mapNotNull null
            }
            SetBindingModel(
                key = setKeyOf(elementFqn),
                elementType = supertypeRefs.getValue(elementFqn),
                elementKeys = impls.map { Key(it.qualifiedName!!.asString()) },
                elementTypes = impls.map { typeRef(it) },
                provenance = site,
                internalElements = impls
                    .filter { it.getVisibility() == Visibility.INTERNAL && it !in implIndex[elementFqn].orEmpty() }
                    .mapTo(mutableSetOf()) { it.qualifiedName!!.asString() },
            )
        }

        return ScanResult(
            bindings = bindings.sortedBy { it.key.id },
            setBindings = setBindings.sortedBy { it.key.id },
            viewModels = viewModels.sortedBy { it.targetType.fqn },
            graphArgs = graphArgs.values.sortedBy { it.name },
            scopes = (ScanResult.BUILT_IN_SCOPES + customScopes).sortedBy { it.level },
            classpathKeys = classpath.provided.entries.associate { (fqn, binding) -> Key(fqn) to binding.scope } +
                // Marked implementations are provided under "qualifier@type".
                classpath.providedQualified.entries.associate { (id, binding) -> Key.parse(id) to binding.scope },
            ambiguousInterfaces = ambiguousInterfaces.sortedBy { it.fqn },
            requiredModules = requiredModules.sorted(),
            issues = issues,
            decisions = decisions.sortedBy { it.id },
            marks = marks.toMap(),
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
            // Contributors can live in any module, and a module only sees itself and
            // what it depends on. Only the application sees them all, so only the
            // application decides that a set is empty — once the classpath's
            // contributors are in (see the set composition in [scan]). Everywhere else
            // the parameter is an ordinary edge on the set's key and the application
            // supplies the binding.
            setConsumers.putIfAbsent(elementFqn, ownerDisplay to site)
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

        // The same mark as on the implementation: it selects which one this parameter
        // wants. Falls through to plain resolution when nothing provides that key, so
        // an annotation Kite knows nothing about stays harmless.
        val qualifier = qualifierOf(parameter)
        if (qualifier != null) {
            val qualified = Key(fqn, qualifier)
            val markedHere = implIndex[fqn].orEmpty().any { qualifierOf(it) == qualifier }
            if (markedHere || qualified.id in classpath.providedQualified) {
                if (!markedHere) {
                    requiredModules += classpath.providedQualified.getValue(qualified.id).module
                }
                return plainDep(qualified)
            }
        }

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
            fqn in classpath.provided -> {
                requiredModules += classpath.provided.getValue(fqn).module
                plainDep(Key(fqn))
            }
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

    /**
     * A qualifier written as an annotation the user owns: the same one on the
     * implementation and on the parameter that wants it.
     *
     * Nothing declares an annotation to *be* a qualifier — both sides are read in
     * their own module, and what ties them together is the key they produce. Only an
     * annotation the project owns is one, decided like every other ownership question
     * ([isProjectType]): a library's `@Serializable` or `@Keep` on an implementation
     * must not move it off its interface. Platform annotations and Kite's own
     * vocabulary never count, even when the build compiles them itself. A qualifier
     * that matches no binding falls back to plain resolution, so an unrelated
     * annotation on a parameter changes nothing.
     */
    private fun qualifierOf(annotated: KSAnnotated): String? {
        for (annotation in annotated.annotations) {
            val declaration = annotation.annotationType.resolve().declaration
            val fqn = declaration.qualifiedName?.asString() ?: continue
            if (NON_QUALIFIER_PACKAGES.any { fqn.startsWith(it) }) continue
            if (declaration !is KSClassDeclaration || !isProjectType(declaration)) continue
            // Remembered with its nesting intact, for codegen's class reference.
            marks.putIfAbsent(fqn, typeRef(declaration))
            return fqn
        }
        return null
    }

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
