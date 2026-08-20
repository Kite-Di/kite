package com.kite.di.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A binding key: `(type, qualifier?)`.
 *
 * [id] is the stable string identity used everywhere (graph node ids, wire protocol,
 * diffing): `"<type-fqn>"` or `"<qualifier>@<type-fqn>"`. Ids derive from the key —
 * not from generation order — so the same logical binding has the same id across
 * rebuilds, restarts and machines.
 *
 * This FQN-string identity is **compile-time/dev-only**: it lives in graph.json (a
 * build-dir artifact) and the board/inspector protocol, never in shipped code. The
 * runtime uses `com.kite.di.runtime.Key` — Class references — so no
 * class-name strings end up in a binary and R8 renaming can't break lookups.
 */
@Serializable
data class Key(val type: String, val qualifier: String? = null) {
    val id: String get() = if (qualifier == null) type else "$qualifier@$type"

    override fun toString(): String = id

    companion object {
        fun parse(id: String): Key {
            val at = id.indexOf('@')
            return if (at < 0) Key(id) else Key(id.substring(at + 1), id.substring(0, at))
        }
    }
}

/** Where a declaration lives: Gradle module, file path (repo-relative), line. */
@Serializable
data class Provenance(
    val gradleModule: String,
    val filePath: String,
    val line: Int,
)

/** "Where it is provided from" — shown on the board and in error messages. */
@Serializable
data class ProvidedBy(
    /** Class name, or `Module.function` for @Provides bindings. */
    val declaration: String,
    val gradleModule: String,
    val file: String,
    val line: Int,
)

@Serializable
enum class NodeKind {
    @SerialName("injectable") INJECTABLE,
    @SerialName("provides") PROVIDES,
    @SerialName("boundInterface") BOUND_INTERFACE,
    /** A member-injection target (Activity/Fragment with @Inject fields) — a consumer that is not itself a binding. */
    @SerialName("entryPoint") ENTRY_POINT,
    /** A `Set<T>` multibinding aggregate — collects @IntoSet contributions. */
    @SerialName("set") SET,
    /** A `Map<String, T>` multibinding aggregate — collects @IntoMap contributions. */
    @SerialName("map") MAP,
    @SerialName("external") EXTERNAL,
}

@Serializable
enum class SiteKind {
    @SerialName("constructorParam") CONSTRUCTOR_PARAM,
    @SerialName("field") FIELD,
    @SerialName("providesParam") PROVIDES_PARAM,
    /** Edge from a Set<T> aggregate to one @IntoSet contribution. */
    @SerialName("setContribution") SET_CONTRIBUTION,
    /** Edge from a Map<String, T> aggregate to one @IntoMap contribution. */
    @SerialName("mapContribution") MAP_CONTRIBUTION,
}

@Serializable
enum class DeferredKind {
    @SerialName("none") NONE,
    @SerialName("provider") PROVIDER,
    @SerialName("lazy") LAZY,
}

/** "Where it is injected in" — exact file:line of the injection site. */
@Serializable
data class SiteRef(val file: String, val line: Int)

@Serializable
data class GraphNode(
    val id: String,
    val type: String,
    val qualifier: String? = null,
    val displayName: String,
    val kind: NodeKind,
    val scope: String? = null,
    val boundTo: List<String> = emptyList(),
    val providedBy: ProvidedBy? = null,
    /**
     * Why the node is wired (schema v2): `"implementation"`
     * (R1 — implements a project interface), `"root-rule"` (R3 — a `@Root`
     * decision), or `"closure"` (R4 — pulled in as a dependency). Null for
     * nodes that are not inferred bindings (entry points, sets, externals).
     */
    val inferredBy: String? = null,
)

@Serializable
data class GraphEdge(
    /** `"<from-id> -> <to-id> # <siteIndex>"` — stable across rebuilds. */
    val id: String,
    /** Consumer node id. */
    val from: String,
    /** Dependency node id. */
    val to: String,
    val siteKind: SiteKind,
    val paramName: String? = null,
    val deferred: DeferredKind = DeferredKind.NONE,
    val site: SiteRef? = null,
) {
    companion object {
        fun edgeId(from: String, to: String, siteIndex: Int): String = "$from -> $to # $siteIndex"
    }
}

@Serializable
data class ScopeDef(val name: String, val level: Int)

@Serializable
data class RuntimeScope(val id: String, val name: String, val parent: String? = null)

@Serializable
data class RuntimeInstance(
    val nodeId: String,
    val scopeId: String,
    val createdAt: Long,
    val creationMicros: Long,
)

/**
 * One "screen resolved a ViewModel" fact. Activity/Fragment call sites of the
 * generated adapters are invisible to static inference, so
 * the inspector accumulates these from runtime events — the board draws them as
 * derived `viewModel` edges.
 */
@Serializable
data class ViewModelUsage(
    /** The ViewModel's graph node id. */
    val nodeId: String,
    /** FQN of the resolving ViewModelStoreOwner's class (Activity/Fragment). */
    val ownerId: String,
    val ownerDisplay: String,
)

@Serializable
data class RuntimeState(
    val openScopes: List<RuntimeScope> = emptyList(),
    val instances: List<RuntimeInstance> = emptyList(),
    /** Accumulated since process start — a late-connecting board still sees them. */
    val viewModelUsages: List<ViewModelUsage> = emptyList(),
)

@Serializable
data class GraphSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Stamped by the inspector at serve time; null in the deterministic build artifact. */
    val generatedAt: String? = null,
    val appId: String,
    val variant: String,
    val scopes: List<ScopeDef> = emptyList(),
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    /** Present only when served by the inspector (live mode). */
    val runtime: RuntimeState? = null,
) {
    companion object {
        /** v2: nodes gained the optional `inferredBy` field. */
        const val SCHEMA_VERSION: Int = 2
    }
}
