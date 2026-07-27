package com.kite.di.processor.validate

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.ScopeDef
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity

/**
 * The Dagger half of the contract: everything the graph *is* must be provably
 * correct at compile time — even though the graph is now inferred, not declared.
 * Message format is standardized: one-line verdict, indented provenance lines,
 * then a `hint:` line.
 */
object GraphValidator {

    const val SUPPRESS_UNUSED = "kite:unused-binding"

    fun validate(scan: ScanResult): List<Issue> {
        val issues = mutableListOf<Issue>()
        val byKey = index(scan.bindings)
        val setKeys = scan.setBindings.associateBy { it.key }
        val argKeys = scan.graphArgs.map { it.key }.toSet()

        issues += scopeLevelCollisions(scan.scopes)
        issues += duplicateBindings(scan)
        // Ambiguous lookups (or an ambiguous scope order) poison the checks below;
        // report only the structural problems first.
        if (issues.any { it.severity == Severity.ERROR }) return issues

        issues += missingBindings(scan, byKey, setKeys.keys, argKeys)
        issues += cycles(scan, byKey)
        issues += scopeViolations(scan.bindings, byKey)
        issues += capturedUnscoped(scan.bindings, byKey)
        issues += unusedBindings(scan)
        return issues
    }

    /** All keys a binding answers to → the binding. Assumes no duplicates. */
    private fun index(bindings: List<BindingModel>): Map<Key, BindingModel> {
        val map = mutableMapOf<Key, BindingModel>()
        for (b in bindings) for (k in listOf(b.key) + b.extraKeys) map.putIfAbsent(k, b)
        return map
    }

    /** Levels order lifetimes (V4 compares them), so two scopes must not share one. */
    private fun scopeLevelCollisions(scopes: List<ScopeDef>): List<Issue> =
        scopes.groupBy { it.level }.filterValues { defs -> defs.map { it.name }.distinct().size > 1 }
            .map { (level, defs) ->
                Issue(
                    Severity.ERROR,
                    "Scope level collision: ${defs.joinToString(" and ") { it.name }} all declare " +
                        "level $level — levels order lifetimes, so each scope needs its own " +
                        "(built-ins: singleton = 0, activity = 1, fragment = 2).",
                )
            }

    // --- V2 ---------------------------------------------------------------------

    private fun duplicateBindings(scan: ScanResult): List<Issue> {
        val claims = mutableMapOf<Key, MutableList<BindingModel>>()
        for (b in scan.bindings) for (k in listOf(b.key) + b.extraKeys) {
            claims.getOrPut(k) { mutableListOf() } += b
        }
        val issues = claims.filterValues { it.size > 1 }.map { (key, owners) ->
            Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Duplicate binding for ${key.id}:")
                    owners.forEachIndexed { i, b ->
                        appendLine("  ${i + 1}) ${b.declaration} (${b.provenance.filePath}:${b.provenance.line})")
                    }
                    append("  hint: keep one implementation, or choose with a `bind` line in graph.rules.")
                },
            )
        }.toMutableList()

        // A plain binding must not claim a Set<T> aggregate key.
        for (set in scan.setBindings) {
            val clash = claims[set.key] ?: continue
            issues += Issue(
                Severity.ERROR,
                "Conflict for ${set.key.id}: bound directly by ${clash.first().declaration} " +
                    "(${clash.first().provenance.filePath}:${clash.first().provenance.line}) while implementations " +
                    "of ${set.elementType.displayName} also aggregate into it.",
            )
        }
        return issues
    }

    // --- V1 ---------------------------------------------------------------------

    private fun missingBindings(
        scan: ScanResult,
        byKey: Map<Key, BindingModel>,
        setKeys: Set<Key>,
        argKeys: Set<Key>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        fun check(d: DependencyModel, wantedBy: String) {
            if (d.optional) return
            if (d.key in byKey || d.key in BUILT_IN_KEYS || d.key in setKeys || d.key in argKeys) return
            issues += Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Missing binding: no provider for ${d.key.id}")
                    appendLine("  injected into: $wantedBy (${d.site.filePath}:${d.site.line})")
                    append("  hint: implement it in a project class, or let the leaf bubble up to Graph.start.")
                },
            )
        }

        for (b in scan.bindings) for (d in b.dependencies) {
            check(d, "${b.declaration}${d.paramName?.let { ", constructor param '$it'" } ?: ""}")
        }
        for (vm in scan.viewModels) for (d in vm.dependencies) {
            check(d, "${vm.targetType.displayName}${d.paramName?.let { ", constructor param '$it'" } ?: ""}")
        }
        return issues
    }

    // --- V3 ---------------------------------------------------------------------

    private fun cycles(scan: ScanResult, byKey: Map<Key, BindingModel>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val setElements: Map<Key, List<BindingModel>> = scan.setBindings.associate { set ->
            set.key to set.elementKeys.mapNotNull { byKey[it] }
        }
        val state = mutableMapOf<BindingModel, Int>() // absent = white, 1 = on path, 2 = done
        val path = mutableListOf<Pair<BindingModel, DependencyModel>>() // (consumer, edge) stack
        val reported = mutableSetOf<Set<String>>()

        fun targetsOf(edge: DependencyModel): List<BindingModel> =
            byKey[edge.key]?.let { listOf(it) } ?: setElements[edge.key] ?: emptyList()

        fun visit(b: BindingModel) {
            if (state[b] == 2) return
            state[b] = 1
            for (d in b.dependencies) {
                // Provider/Lazy edges defer construction — a cycle through them is constructible.
                if (d.deferred != DeferredKind.NONE) continue
                for (dep in targetsOf(d)) {
                    when (state[dep]) {
                        2 -> Unit
                        1 -> {
                            val start = path.indexOfFirst { it.first === dep }
                            val cycleEdges = path.drop(if (start >= 0) start else path.size) + (b to d)
                            if (reported.add(cycleEdges.map { it.first.declaration }.toSet())) {
                                issues += Issue(Severity.ERROR, cycleMessage(cycleEdges))
                            }
                        }
                        else -> {
                            path += b to d
                            visit(dep)
                            path.removeAt(path.lastIndex)
                        }
                    }
                }
            }
            state[b] = 2
        }

        for (b in scan.bindings) visit(b)
        return issues
    }

    private fun cycleMessage(cycleEdges: List<Pair<BindingModel, DependencyModel>>): String =
        buildString {
            appendLine("Dependency cycle detected:")
            for ((consumer, edge) in cycleEdges) {
                appendLine(
                    "  ${consumer.declaration} (${consumer.provenance.filePath}:${consumer.provenance.line}) → " +
                        "${edge.key.id} (constructor param '${edge.paramName}', ${edge.site.filePath}:${edge.site.line})"
                )
            }
            append("  hint: break the cycle by injecting Provider<T> or Lazy<T> at one of the sites.")
        }

    // --- V4 ---------------------------------------------------------------------

    private fun scopeViolations(bindings: List<BindingModel>, byKey: Map<Key, BindingModel>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for (b in bindings) {
            val level = b.scopeLevel ?: continue // unscoped may depend on anything
            for (d in b.dependencies) {
                val depLevel = byKey[d.key]?.scopeLevel ?: BUILT_IN_KEYS[d.key] ?: continue
                val dep = byKey[d.key]
                if (depLevel > level) {
                    issues += Issue(
                        Severity.ERROR,
                        buildString {
                            appendLine("Scope violation: ${b.scopeName} ${b.declaration} depends on ${dep?.scopeName} ${d.key.id}")
                            appendLine("  ${b.scopeName} ${b.declaration} (${b.provenance.filePath}:${b.provenance.line})")
                            dep?.let {
                                appendLine(
                                    "  depends on ${it.scopeName} ${it.declaration} (${it.provenance.filePath}:${it.provenance.line}) " +
                                        "via constructor param '${d.paramName}' (${d.site.filePath}:${d.site.line})"
                                )
                            }
                            append("  hint: a longer-lived binding cannot depend on a shorter-lived one — adjust a `scope` line in graph.rules.")
                        },
                    )
                }
            }
        }
        return issues
    }

    /** ⚠ scoped-captures-unscoped: legal, but surprising when the unscoped binding has other consumers. */
    private fun capturedUnscoped(bindings: List<BindingModel>, byKey: Map<Key, BindingModel>): List<Issue> {
        val consumerCount = mutableMapOf<Key, Int>()
        for (b in bindings) for (d in b.dependencies) {
            val canonical = byKey[d.key]?.key ?: continue
            consumerCount[canonical] = (consumerCount[canonical] ?: 0) + 1
        }
        val issues = mutableListOf<Issue>()
        for (b in bindings) {
            if (b.scopeLevel == null) continue
            for (d in b.dependencies) {
                val dep = byKey[d.key] ?: continue
                if (dep.scopeLevel != null) continue
                if ((consumerCount[dep.key] ?: 0) <= 1) continue
                issues += Issue(
                    Severity.WARNING,
                    "${b.scopeName} ${b.declaration} captures unscoped ${dep.key.id} for its whole lifetime " +
                        "(${d.site.filePath}:${d.site.line}), while other consumers get fresh instances — " +
                        "add `scope ${dep.key.type} -> singleton` to graph.rules if sharing is intended.",
                )
            }
        }
        return issues
    }

    // --- V5 (partial: unused) -----------------------------------------------------

    /**
     * ⚠ Only isolated bindings are flagged (no consumers *and* no dependencies):
     * bindings with dependencies are usually roots resolved at runtime via
     * `by injected()` / `Kite.get`, which the processor cannot see. Bindings
     * included by a `root` rule carry an implicit suppression — being resolved at
     * runtime is their reason to exist.
     */
    private fun unusedBindings(scan: ScanResult): List<Issue> {
        val consumed = buildSet {
            for (b in scan.bindings) for (d in b.dependencies) add(d.key)
            for (vm in scan.viewModels) for (d in vm.dependencies) add(d.key)
            for (set in scan.setBindings) addAll(set.elementKeys)
        }
        return scan.bindings
            .filter { b ->
                b.dependencies.isEmpty() &&
                    (listOf(b.key) + b.extraKeys).none { it in consumed } &&
                    SUPPRESS_UNUSED !in b.suppressions
            }
            .map { b ->
                Issue(
                    Severity.WARNING,
                    "Unused binding: ${b.declaration} (${b.provenance.filePath}:${b.provenance.line}) is never injected. " +
                        "Add `root ${b.key.type}` to graph.rules if it is resolved dynamically, or delete the class.",
                )
            }
    }
}
