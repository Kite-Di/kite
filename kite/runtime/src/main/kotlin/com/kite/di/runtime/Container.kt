package com.kite.di.runtime

import com.kite.di.runtime.observe.GraphEvent
import com.kite.di.runtime.observe.GraphEvents

/**
 * The binding table + per-scope instance caches. Bindings are immutable after
 * construction; resolution is map lookups and generated-factory constructor calls —
 * no reflection.
 */
class Container(
    registries: List<BindingRegistry>,
    val scopeTree: ScopeTree = ScopeTree(),
    /** Environment-provided bindings (Application, Context) added by [Kite.init]. */
    builtIns: List<BindingRecord> = emptyList(),
) : Resolver {

    private val bindings: Map<Key, BindingRecord>

    init {
        val map = LinkedHashMap<Key, BindingRecord>()
        for (record in builtIns + registries.flatMap { it.bindings() }) {
            for (key in listOf(record.key) + record.extraKeys) {
                val previous = map.put(key, record)
                if (previous != null && previous !== record) {
                    // Compile-time validation makes this unreachable for one module;
                    // guard against conflicting pre-built registries anyway.
                    throw KiteException(
                        "Duplicate binding for $key:\n" +
                            "  1) ${describe(previous)}\n" +
                            "  2) ${describe(record)}\n" +
                            "  hint: keep one implementation, or choose with a `bind` line in graph.rules."
                    )
                }
            }
        }
        bindings = map
    }

    fun record(key: Key): BindingRecord? = bindings[key]

    override fun <T : Any> resolve(key: Key, scope: ScopeNode): T {
        val record = bindings[key] ?: failMissing(key, scope)
        val scopeLevel = record.scopeLevel
            ?: return createTracked(record, scope) // unscoped: new instance per injection

        val node = scope.ancestorAtLevel(scopeLevel) ?: run {
            val message = buildString {
                append("No open ${record.scopeName ?: "scope level $scopeLevel"} scope while resolving ")
                append("@${record.scopeName} ${key.id}\n")
                record.provenance?.let { append("  provided by: ${record.declaration} (${it.filePath}:${it.line})\n") }
                append("  current scope path: ${scope.scopePath().joinToString(" > ")}\n")
                append("  hint: resolve from an Activity/Fragment, or change the binding's scope.")
            }
            GraphEvents.emit(GraphEvent.ResolutionFailed(key, scope.scopePath(), message))
            throw KiteException(message)
        }

        // Cache under the record's canonical key so aliases (bindTo) share the instance.
        val cacheKey = record.key
        @Suppress("UNCHECKED_CAST")
        node.instances[cacheKey]?.let { return it as T }
        synchronized(node.lockFor(cacheKey)) {
            @Suppress("UNCHECKED_CAST")
            node.instances[cacheKey]?.let { return it as T }
            val created: T = createTracked(record, node)
            node.instances[cacheKey] = created
            return created
        }
    }

    override fun <T : Any> provider(key: Key, scope: ScopeNode): Provider<T> = object : Provider<T> {
        override fun get(): T = resolve(key, scope)
    }

    override fun <T : Any> deferred(key: Key, scope: ScopeNode): Lazy<T> = Lazy(provider(key, scope))

    private fun <T : Any> createTracked(record: BindingRecord, cacheScope: ScopeNode): T {
        val start = System.nanoTime()
        @Suppress("UNCHECKED_CAST")
        val instance = (record.factory as Factory<T>).create(this, cacheScope)
        val micros = (System.nanoTime() - start) / 1_000
        val createdAt = System.currentTimeMillis()
        if (record.scopeLevel != null) {
            cacheScope.instanceMeta[record.key] = ScopeNode.InstanceMeta(createdAt, micros)
        }
        GraphEvents.emit(
            GraphEvent.InstanceCreated(record.key, cacheScope.id, cacheScope.scopePath(), createdAt, micros)
        )
        return instance
    }

    private fun failMissing(key: Key, scope: ScopeNode): Nothing {
        val nearMisses = bindings.values
            .filter { it.key.type == key.type && it.key != key }
            .distinctBy { it.key }
        val message = buildString {
            append("No binding for ${key.id}\n")
            append("  current scope path: ${scope.scopePath().joinToString(" > ")}\n")
            append("  hint: implement a project interface of that type, or add `root ${key.type.name}` to graph.rules for classes resolved only at runtime.")
            for (near in nearMisses) {
                append("\n  note: a binding for ${near.key.id} exists")
                near.provenance?.let { append(" (${it.filePath}:${it.line})") }
                append(" — did you mean that qualifier?")
            }
        }
        GraphEvents.emit(GraphEvent.ResolutionFailed(key, scope.scopePath(), message))
        throw KiteException(message)
    }

    private fun describe(record: BindingRecord): String = buildString {
        append(record.declaration ?: record.key.id)
        record.provenance?.let { append(" (${it.filePath}:${it.line})") }
    }
}
