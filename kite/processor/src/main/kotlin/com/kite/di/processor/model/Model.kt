package com.kite.di.processor.model

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind

/**
 * Compiler-independent model produced by the scanner. The validator, code
 * generators and graph exporter operate only on this — which is what makes them
 * unit-testable without running KSP.
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

enum class BindingDeclKind { INJECTABLE, PROVIDES }

data class DependencyModel(
    val key: Key,
    val type: TypeRef,
    val siteKind: SiteKind,
    val deferred: DeferredKind = DeferredKind.NONE,
    val paramName: String? = null,
    /** Kotlin default value present — the dependency is optional. */
    val optional: Boolean = false,
    /** Non-null when the site injects `Set<T>`: the element type (multibinding). */
    val setElement: TypeRef? = null,
    /** Non-null when the site injects `Map<String, V>`: the value type (multibinding). */
    val mapValue: TypeRef? = null,
    val site: Provenance,
)

/** The synthetic key a `Set<T>` multibinding is registered and resolved under. */
fun setKeyOf(elementFqn: String, qualifier: String?): Key =
    Key("kotlin.collections.Set<$elementFqn>", qualifier)

/** The synthetic key a `Map<String, V>` multibinding is registered and resolved under. */
fun mapKeyOf(valueFqn: String, qualifier: String?): Key =
    Key("kotlin.collections.Map<kotlin.String,$valueFqn>", qualifier)

data class BindingModel(
    val key: Key,
    val keyType: TypeRef,
    val extraKeys: List<Key> = emptyList(),
    val extraKeyTypes: List<TypeRef> = emptyList(),
    val declKind: BindingDeclKind,
    val scopeLevel: Int? = null,
    val scopeName: String? = null,
    /** "RealUserRepo" or "NetworkModule.provideOkHttp" — for messages and the board. */
    val declaration: String,
    val provenance: Provenance,
    val dependencies: List<DependencyModel> = emptyList(),
    val suppressions: Set<String> = emptySet(),
    // --- codegen info ---
    /** Class to construct (INJECTABLE) or the @Module class (PROVIDES). */
    val targetType: TypeRef,
    val providesFunction: String? = null,
    val moduleIsObject: Boolean = true,
    /** @IntoSet contribution: [key] is the element key; registered under [setKey]. */
    val intoSet: Boolean = false,
    /** @IntoMap contribution: the entry key; [key] is the value key, registered under [mapKey]. */
    val intoMapKey: String? = null,
) {
    /** The Set<T> aggregate key this contribution belongs to (null unless [intoSet]). */
    val setKey: Key? get() = if (intoSet) setKeyOf(key.type, key.qualifier) else null

    /** The Map<String, V> aggregate key this contribution belongs to (null unless @IntoMap). */
    val mapKey: Key? get() = if (intoMapKey != null) mapKeyOf(key.type, key.qualifier) else null

    /** True for any multibinding contribution — excluded from plain key indexing. */
    val isContribution: Boolean get() = intoSet || intoMapKey != null

    val factoryName: String
        get() = when (declKind) {
            BindingDeclKind.INJECTABLE -> targetType.simpleNames.joinToString("_") + "_Factory"
            BindingDeclKind.PROVIDES ->
                targetType.simpleNames.joinToString("_") + "_" + providesFunction + "_Factory"
        }
    val factoryPackage: String get() = targetType.packageName
}

data class FieldInjectionModel(
    val fieldName: String,
    val key: Key,
    val type: TypeRef,
    val deferred: DeferredKind = DeferredKind.NONE,
    val site: Provenance,
)

data class MemberInjectModel(
    val targetType: TypeRef,
    val fields: List<FieldInjectionModel>,
    val provenance: Provenance,
) {
    val injectorName: String get() = targetType.simpleNames.joinToString("_") + "_MemberInjector"
}

data class ScanResult(
    val bindings: List<BindingModel> = emptyList(),
    val memberInjects: List<MemberInjectModel> = emptyList(),
    /** Built-in scopes plus user-declared @Scope annotations discovered in sources. */
    val scopes: List<ScopeDef> = BUILT_IN_SCOPES,
    /** Structural (V5) problems found while scanning. */
    val issues: List<Issue> = emptyList(),
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
