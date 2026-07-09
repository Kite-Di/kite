package com.kite.di.processor.validate

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.processor.model.BUILT_IN_KEYS
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.MemberInjectModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity

/**
 * The Dagger half of the contract: everything the graph *is* must be provably
 * correct at compile time. Message format is standardized: one-line verdict,
 * indented provenance lines, then a `hint:` line.
 */
object GraphValidator {

    const val SUPPRESS_UNUSED = "kite:unused-binding"

    fun validate(scan: ScanResult): List<Issue> {
        val issues = mutableListOf<Issue>()
        val byKey = index(scan.bindings)

        issues += duplicateBindings(scan.bindings)
        // Duplicates make key->binding lookups ambiguous; report only them first.
        if (issues.any { it.severity == Severity.ERROR }) return issues

        issues += missingBindings(scan, byKey)
        issues += cycles(scan.bindings, byKey)
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

    // --- V2 ---------------------------------------------------------------------

    private fun duplicateBindings(bindings: List<BindingModel>): List<Issue> {
        val claims = mutableMapOf<Key, MutableList<BindingModel>>()
        for (b in bindings) for (k in listOf(b.key) + b.extraKeys) claims.getOrPut(k) { mutableListOf() } += b
        return claims.filterValues { it.size > 1 }.map { (key, owners) ->
            Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Duplicate binding for ${key.id}:")
                    owners.forEachIndexed { i, b ->
                        appendLine("  ${i + 1}) ${b.declaration} (${b.provenance.filePath}:${b.provenance.line})")
                    }
                    append("  hint: keep one, or distinguish them with qualifiers (@Named).")
                },
            )
        }
    }

    // --- V1 ---------------------------------------------------------------------

    private fun missingBindings(scan: ScanResult, byKey: Map<Key, BindingModel>): List<Issue> {
        val issues = mutableListOf<Issue>()

        fun check(key: Key, optional: Boolean, wantedBy: String, site: String) {
            if (optional) return
            if (key in byKey || key in BUILT_IN_KEYS) return
            issues += Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Missing binding: no provider for ${key.id}")
                    appendLine("  injected into: $wantedBy ($site)")
                    append("  hint: annotate a class with @Injectable, or add a @Provides function returning ")
                    append("${key.id} to a @Module.")
                    for (near in nearMisses(key, byKey)) {
                        append("\n  note: ${near.first} (${near.second}) — did you mean that?")
                    }
                },
            )
        }

        for (b in scan.bindings) for (d in b.dependencies) {
            check(
                d.key, d.optional,
                wantedBy = "${b.declaration}${d.paramName?.let { ", ${describeSite(d)} '$it'" } ?: ""}",
                site = "${d.site.filePath}:${d.site.line}",
            )
        }
        for (m in scan.memberInjects) for (f in m.fields) {
            check(
                f.key, optional = false,
                wantedBy = "${m.targetType.displayName}, field '${f.fieldName}'",
                site = "${f.site.filePath}:${f.site.line}",
            )
        }
        return issues
    }

    private fun describeSite(d: DependencyModel): String = when (d.siteKind) {
        com.kite.di.graph.SiteKind.CONSTRUCTOR_PARAM -> "constructor param"
        com.kite.di.graph.SiteKind.PROVIDES_PARAM -> "provides param"
        com.kite.di.graph.SiteKind.FIELD -> "field"
    }

    /** Kills the classic head-scratcher: same type under another (or no) qualifier. */
    private fun nearMisses(key: Key, byKey: Map<Key, BindingModel>): List<Pair<String, String>> =
        byKey.entries
            .filter { it.key.type == key.type && it.key != key }
            .map { (k, b) ->
                val what = if (k.qualifier == null) {
                    "an unqualified binding for ${k.type} exists"
                } else {
                    "a binding for ${k.id} exists"
                }
                what to "${b.provenance.filePath}:${b.provenance.line}"
            }

    // --- V3 ---------------------------------------------------------------------

    private fun cycles(bindings: List<BindingModel>, byKey: Map<Key, BindingModel>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val state = mutableMapOf<Key, Int>() // absent = white, 1 = on current path, 2 = done
        val path = mutableListOf<Pair<BindingModel, DependencyModel>>() // (consumer, edge) stack
        val reported = mutableSetOf<Set<Key>>()

        fun visit(b: BindingModel) {
            if (state[b.key] == 2) return
            state[b.key] = 1
            for (d in b.dependencies) {
                // Provider/Lazy edges defer construction — a cycle through them is constructible.
                if (d.deferred != DeferredKind.NONE) continue
                val dep = byKey[d.key] ?: continue
                when (state[dep.key]) {
                    2 -> Unit
                    1 -> {
                        val start = path.indexOfFirst { it.first.key == dep.key }
                        val cycleEdges = path.drop(if (start >= 0) start else path.size) + (b to d)
                        if (reported.add(cycleEdges.map { it.first.key }.toSet())) {
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
            state[b.key] = 2
        }

        for (b in bindings) visit(b)
        return issues
    }

    private fun cycleMessage(cycleEdges: List<Pair<BindingModel, DependencyModel>>): String =
        buildString {
            appendLine("Dependency cycle detected:")
            for ((consumer, edge) in cycleEdges) {
                appendLine(
                    "  ${consumer.declaration} (${consumer.provenance.filePath}:${consumer.provenance.line}) → " +
                        "${edge.key.id} (${describeSite(edge)} '${edge.paramName}', ${edge.site.filePath}:${edge.site.line})"
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
                            appendLine("Scope violation: @${b.scopeName} ${b.declaration} depends on @${dep?.scopeName} ${d.key.id}")
                            appendLine("  @${b.scopeName} ${b.declaration} (${b.provenance.filePath}:${b.provenance.line})")
                            dep?.let {
                                appendLine(
                                    "  depends on @${it.scopeName} ${it.declaration} (${it.provenance.filePath}:${it.provenance.line}) " +
                                        "via ${describeSite(d)} '${d.paramName}' (${d.site.filePath}:${d.site.line})"
                                )
                            }
                            append("  hint: a longer-lived binding cannot depend on a shorter-lived one.")
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
                    "@${b.scopeName} ${b.declaration} captures unscoped ${dep.key.id} for its whole lifetime " +
                        "(${d.site.filePath}:${d.site.line}), while other consumers get fresh instances — " +
                        "consider scoping ${dep.declaration} explicitly if sharing is intended.",
                )
            }
        }
        return issues
    }

    // --- V5 (partial: unused) -----------------------------------------------------

    /**
     * ⚠ Only isolated bindings are flagged (no consumers *and* no dependencies):
     * bindings with dependencies are usually roots resolved at runtime via
     * `by injected()` / `Kite.get`, which the processor cannot see.
     */
    private fun unusedBindings(scan: ScanResult): List<Issue> {
        val consumed = buildSet {
            for (b in scan.bindings) for (d in b.dependencies) add(d.key)
            for (m in scan.memberInjects) for (f in m.fields) add(f.key)
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
                        "Suppress with @Suppress(\"$SUPPRESS_UNUSED\") if it is resolved dynamically.",
                )
            }
    }
}
