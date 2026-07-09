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
    @SerialName("external") EXTERNAL,
}

@Serializable
enum class SiteKind {
    @SerialName("constructorParam") CONSTRUCTOR_PARAM,
    @SerialName("field") FIELD,
    @SerialName("providesParam") PROVIDES_PARAM,
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

@Serializable
data class RuntimeState(
    val openScopes: List<RuntimeScope> = emptyList(),
    val instances: List<RuntimeInstance> = emptyList(),
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
        const val SCHEMA_VERSION: Int = 1
    }
}
