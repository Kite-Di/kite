package com.kite.di.processor.validate

import com.kite.di.graph.Key
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.ScanResult

/**
 * What resolving a key pins: the shortest lifetime it reaches.
 *
 * A scoped binding pins its own scope. An unscoped binding and a `Set<I>` are built in
 * the *consumer's* scope, so they reach whatever their dependencies reach — a singleton
 * holding a `@Fresh` helper that wants an Activity is as tied to the Activity as one
 * depending on it directly. Another module's unscoped key reaches what that module
 * exported (`@ProvidedKeys.reach`).
 *
 * One computation for every question that needs it: V4 (may this binding depend on
 * that one?), and where a lookup or a ViewModel adapter may be called from at all.
 */
class ScopeReach(private val scan: ScanResult) {

    /** [level] pinned by [target]; [through] names what lies between, nearest first. */
    data class Reach(val level: Int, val scopeName: String?, val target: Key, val through: List<String>)

    private val byKey: Map<Key, BindingModel> = buildMap {
        for (b in scan.bindings) for (k in b.answeredKeys) putIfAbsent(k, b)
    }
    private val sets = scan.setBindings.associateBy { it.key }
    private val memo = HashMap<Key, Reach?>()
    private val visiting = HashSet<Key>()

    fun of(key: Key): Reach? {
        if (key in memo) return memo[key]
        if (!visiting.add(key)) return null // a deferred cycle; V3 owns cycles
        val binding = byKey[key]
        val set = sets[key]
        val result = when {
            binding?.scopeLevel != null -> Reach(binding.scopeLevel, binding.scopeName, key, emptyList())
            binding != null -> binding.dependencies
                .mapNotNull { d -> of(d.key)?.let { it.copy(through = listOf(binding.declaration) + it.through) } }
                .maxByOrNull { it.level }
            set != null -> set.elementKeys
                .mapNotNull { e -> of(e)?.let { it.copy(through = listOf("Set<${set.elementType.displayName}>") + it.through) } }
                .maxByOrNull { it.level }
            key in scan.classpathKeys -> scan.classpathKeys[key]?.let { Reach(it.level, it.name, key, emptyList()) }
                ?: scan.classpathReach[key]?.let { level -> Reach(level, scopeName(level), key, emptyList()) }
            else -> BUILT_IN_KEYS[key]?.let { Reach(it, null, key, emptyList()) }
        }
        visiting.remove(key)
        memo[key] = result
        return result
    }

    /** The shortest lifetime among [dependencies] — what building something from them pins. */
    fun ofDependencies(dependencies: List<DependencyModel>): Int =
        dependencies.mapNotNull { of(it.key)?.level }.maxOrNull() ?: 0

    /** For export: an unscoped key's reach, when it reaches anything scoped beyond the app. */
    fun exported(key: Key): Int? {
        val binding = byKey[key] ?: return null
        if (binding.scopeLevel != null) return null // the scope itself is exported
        return of(key)?.level?.takeIf { it > 0 }
    }

    private fun scopeName(level: Int): String? = scan.scopes.firstOrNull { it.level == level }?.name
}
