package com.kite.di.processor.model

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind

/**
 * Compiler-independent model produced by the inference scanner. The validator,
 * code generators and graph exporter operate only on this — which is what makes
 * them unit-testable without running KSP.
 */

/** A type reference with enough structure to build a KotlinPoet ClassName. */
data class TypeRef(
    val packageName: String,
    /** Nested classes keep their path: `["Outer", "Inner"]`. */
    val simpleNames: List<String>,
) {
    val fqn: String get() = (listOf(packageName) + simpleNames).filter { it.isNotEmpty() }.joinToString(".")
    val displayName: String get() = simpleNames.joinToString(".")
}

/** Which inference rule included a binding — provenance for errors, graph.json and the board. */
enum class InferredBy(val wire: String) {
    /** R1: concrete class implementing a project interface. */
    IMPLEMENTATION("implementation"),
    /** R3: a `@Root` decision. */
    ROOT_RULE("root-rule"),
    /** R4: pulled in as a constructor dependency of an included binding. */
    CLOSURE("closure"),
}

data class DependencyModel(
    val key: Key,
    val type: TypeRef,
    val siteKind: SiteKind,
    val deferred: DeferredKind = DeferredKind.NONE,
    val paramName: String? = null,
    /** Kotlin default value present — the dependency is optional. */
    val optional: Boolean = false,
    /** Non-null when the site injects `Set<T>`: the element type (inferred multibinding). */
    val setElement: TypeRef? = null,
    /** True when [key] is a graph argument (leaf) — resolved from `Graph.start`. */
    val isGraphArg: Boolean = false,
    val site: Provenance,
)

/** The synthetic key a `Set<T>` multibinding is registered and resolved under. */
fun setKeyOf(elementFqn: String): Key = Key("kotlin.collections.Set<$elementFqn>")

data class BindingModel(
    val key: Key,
    val keyType: TypeRef,
    /** Interfaces this implementation is bound to (sole implementation, or chosen by a `bind` rule). */
    val extraKeys: List<Key> = emptyList(),
    val extraKeyTypes: List<TypeRef> = emptyList(),
    val scopeLevel: Int? = null,
    val scopeName: String? = null,
    /** Class simple name — for messages and the board. */
    val declaration: String,
    val inferredBy: InferredBy = InferredBy.CLOSURE,
    val provenance: Provenance,
    val dependencies: List<DependencyModel> = emptyList(),
    val suppressions: Set<String> = emptySet(),
    /** Class to construct — always the binding's own class in the inferred model. */
    val targetType: TypeRef,
    /**
     * `internal` in Kotlin. The generated factory has to carry the same modifier:
     * a public factory returning an internal type does not compile. The key holder
     * generated next to it stays public — that is the handle other modules use.
     */
    val internal: Boolean = false,
) {
    val factoryName: String get() = targetType.simpleNames.joinToString("_") + "_Factory"
    val factoryPackage: String get() = targetType.packageName
}

/**
 * An inferred `Set<I>` multibinding: one record aggregating every implementation
 * of `I`. Elements resolve through their own keys, so each respects its own scope.
 */
data class SetBindingModel(
    val key: Key,
    val elementType: TypeRef,
    /** Own keys of the implementation bindings, sorted by id. */
    val elementKeys: List<Key>,
    val elementTypes: List<TypeRef>,
    /** First consumer site — provenance for messages and the board. */
    val provenance: Provenance,
)

/** One constructor parameter of a ViewModel, in declaration order. */
sealed interface ViewModelParam {
    val name: String

    /** Resolved from the graph. */
    data class Injected(override val name: String, val dependency: DependencyModel) : ViewModelParam

    /** Passed by the caller — a parameter of the generated adapter. */
    data class Runtime(override val name: String, val type: TypeRef) : ViewModelParam

    /** `SavedStateHandle` — supplied from androidx CreationExtras. */
    data class SavedState(override val name: String) : ViewModelParam
}

/** R2: a ViewModel entry point — gets generated Activity/Fragment adapters, is not a binding. */
data class ViewModelModel(
    val targetType: TypeRef,
    val params: List<ViewModelParam>,
    val provenance: Provenance,
) {
    val adapterName: String
        get() = targetType.simpleNames.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            .replaceFirstChar(Char::lowercaseChar)

    val dependencies: List<DependencyModel>
        get() = params.filterIsInstance<ViewModelParam.Injected>().map { it.dependency }
}

/**
 * R5: a leaf constructor parameter nothing provides — becomes a parameter of the
 * generated `Graph.start(...)`, registered as an instance binding under
 * `Key(type, qualifier = name)`.
 */
data class GraphArg(
    val name: String,
    val type: TypeRef,
    val sites: List<Provenance>,
) {
    val key: Key get() = Key(type.fqn, qualifier = name)
}

/** One key another Gradle module's registry provides (read from `@ProvidedKeys`). */
data class ClasspathBinding(
    /** null = unscoped. */
    val scope: ScopeDef?,
    /** Gradle path of the providing module, e.g. ":core" — for error messages. */
    val module: String,
    /**
     * `path:line` where the other module declares it, or null when that module was
     * built with provenance stripped. KSP gives binary declarations no source
     * position of their own, so this is the only way a message about another
     * module's class can name a line.
     */
    val at: String? = null,
)

/**
 * What the compile classpath provides: keys exported by other source modules'
 * registries, discovered via their `@ProvidedKeys` annotations. A module *exports*
 * its interface implementations, its `@Root`s, and their constructor closure —
 * a plain class wanted only downstream needs a `@Root` in the owning module.
 */
data class ClasspathIndex(
    /** key type fqn → the providing module's binding. */
    val provided: Map<String, ClasspathBinding> = emptyMap(),
    /** interface fqn with >1 implementation and no `@Bind` decision → owning module. */
    val ambiguous: Map<String, String> = emptyMap(),
    /** `qualifier@type` → the providing module's binding, for marked implementations. */
    val providedQualified: Map<String, ClasspathBinding> = emptyMap(),
) {
    companion object {
        val EMPTY = ClasspathIndex()
    }
}

data class ScanResult(
    val bindings: List<BindingModel> = emptyList(),
    val setBindings: List<SetBindingModel> = emptyList(),
    val viewModels: List<ViewModelModel> = emptyList(),
    val graphArgs: List<GraphArg> = emptyList(),
    /** Built-in scopes plus custom scopes declared by `@Scoped` rules. */
    val scopes: List<ScopeDef> = BUILT_IN_SCOPES,
    /** Keys other source modules provide (from `@ProvidedKeys`) — V1/V4 consult these. */
    val classpathKeys: Map<Key, ScopeDef?> = emptyMap(),
    /** This module's interfaces with >1 implementation and no `@Bind` — exported via `@ProvidedKeys`. */
    val ambiguousInterfaces: List<TypeRef> = emptyList(),
    /** Structural problems found while scanning (rules errors, ambiguities, leaves in conflict). */
    val issues: List<Issue> = emptyList(),
    /** Ambiguities as structured decisions — exported to decisions.json for board cards. */
    val decisions: List<com.kite.di.graph.PendingDecision> = emptyList(),
) {
    companion object {
        val BUILT_IN_SCOPES = listOf(
            ScopeDef("Singleton", 0),
            ScopeDef("ActivityScoped", 1),
            ScopeDef("FragmentScoped", 2),
        )
    }
}

enum class Severity { ERROR, WARNING }

data class Issue(val severity: Severity, val message: String)

/** Keys the runtime provides without any user declaration (see Kite.init). */
val BUILT_IN_KEYS: Map<Key, Int> = mapOf(
    Key("android.app.Application") to 0,
    Key("android.content.Context") to 0,
)
