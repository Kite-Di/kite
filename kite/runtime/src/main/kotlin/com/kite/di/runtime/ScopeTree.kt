package com.kite.di.runtime

import com.kite.di.graph.RuntimeInstance
import com.kite.di.graph.RuntimeScope
import com.kite.di.runtime.observe.GraphEvent
import com.kite.di.runtime.observe.GraphEvents
import java.util.concurrent.ConcurrentHashMap

data class ScopeId(val value: String) {
    override fun toString(): String = value

    companion object {
        val App = ScopeId("app")
    }
}

class ScopeNode internal constructor(
    val id: ScopeId,
    /** Scope annotation name: "Singleton", "ActivityScoped", custom. */
    val name: String,
    val level: Int,
    val parent: ScopeNode?,
) {
    internal val instances = ConcurrentHashMap<Key, Any>()
    internal val instanceMeta = ConcurrentHashMap<Key, InstanceMeta>()
    private val locks = ConcurrentHashMap<Key, Any>()

    internal fun lockFor(key: Key): Any {
        // Not computeIfAbsent: that needs API 24 and minSdk is 23.
        locks[key]?.let { return it }
        val candidate = Any()
        return locks.putIfAbsent(key, candidate) ?: candidate
    }

    fun ancestorAtLevel(level: Int): ScopeNode? {
        var node: ScopeNode? = this
        while (node != null && node.level != level) node = node.parent
        return node
    }

    fun scopePath(): List<ScopeId> =
        generateSequence(this) { it.parent }.map { it.id }.toList().asReversed()

    internal class InstanceMeta(val createdAt: Long, val creationMicros: Long)
}

/**
 * The open-scopes hierarchy: App (level 0) → Activity (1) → Fragment (2), plus
 * user-opened custom scopes. Thread-safe; scope open/close emits [GraphEvent]s.
 */
class ScopeTree(appScopeName: String = "Singleton") {
    val root = ScopeNode(ScopeId.App, appScopeName, 0, null)
    private val nodes = ConcurrentHashMap<ScopeId, ScopeNode>().apply { put(ScopeId.App, root) }

    fun find(id: ScopeId): ScopeNode? = nodes[id]

    fun open(id: ScopeId, name: String, level: Int, parent: ScopeId = ScopeId.App): ScopeNode {
        val parentNode = nodes[parent]
            ?: throw KiteException("Cannot open scope '$id': parent scope '$parent' is not open.")
        val created = ScopeNode(id, name, level, parentNode)
        val existing = nodes.putIfAbsent(id, created)
        if (existing != null) return existing
        GraphEvents.emit(GraphEvent.ScopeOpened(id, name, parent))
        return created
    }

    fun close(id: ScopeId) {
        if (id == ScopeId.App) throw KiteException("The app scope cannot be closed.")
        // Close the whole subtree: children first.
        val descendants = nodes.values.filter { node ->
            generateSequence(node.parent) { it.parent }.any { it.id == id }
        }
        for (node in descendants.sortedByDescending { it.level }) {
            if (nodes.remove(node.id) != null) GraphEvents.emit(GraphEvent.ScopeClosed(node.id))
        }
        if (nodes.remove(id) != null) GraphEvents.emit(GraphEvent.ScopeClosed(id))
    }

    fun openScopes(): List<RuntimeScope> =
        nodes.values.sortedWith(compareBy({ it.level }, { it.id.value }))
            .map { RuntimeScope(it.id.value, it.name, it.parent?.id?.value) }

    fun instances(): List<RuntimeInstance> = nodes.values.flatMap { node ->
        node.instances.keys.mapNotNull { key ->
            node.instanceMeta[key]?.let { meta ->
                RuntimeInstance(key.id, node.id.value, meta.createdAt, meta.creationMicros)
            }
        }
    }.sortedWith(compareBy({ it.scopeId }, { it.nodeId }))
}
