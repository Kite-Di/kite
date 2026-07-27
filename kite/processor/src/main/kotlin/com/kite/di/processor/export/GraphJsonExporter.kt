package com.kite.di.processor.export

import com.kite.di.graph.GraphEdge
import com.kite.di.graph.GraphNode
import com.kite.di.graph.GraphSnapshot
import com.kite.di.graph.Key
import com.kite.di.graph.NodeKind
import com.kite.di.graph.ProvidedBy
import com.kite.di.graph.Provenance
import com.kite.di.graph.SiteKind
import com.kite.di.graph.SiteRef
import com.kite.di.processor.model.ScanResult

/**
 * Model → [GraphSnapshot]. Output is deterministic (nodes/edges sorted by id, no
 * timestamp) so `graph.json` diffs cleanly in CI. The inspector stamps
 * `generatedAt` and the `runtime` section at serve time. Schema v1 — unchanged by
 * the inferred paradigm: inferred class bindings export as `injectable` nodes,
 * ViewModels as `entryPoint` nodes, graph arguments surface as `external` nodes.
 */
object GraphJsonExporter {

    fun export(scan: ScanResult, appId: String, variant: String): GraphSnapshot {
        val nodes = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeIndex = mutableMapOf<Pair<String, String>, Int>()

        fun addEdge(from: String, to: Key, siteKind: SiteKind, paramName: String?, deferred: com.kite.di.graph.DeferredKind, site: SiteRef) {
            val index = edgeIndex.merge(from to to.id, 0) { old, _ -> old + 1 } ?: 0
            edges += GraphEdge(
                id = GraphEdge.edgeId(from, to.id, index),
                from = from,
                to = to.id,
                siteKind = siteKind,
                paramName = paramName,
                deferred = deferred,
                site = site,
            )
        }

        // Inferred class bindings.
        for (b in scan.bindings) {
            nodes[b.key.id] = GraphNode(
                id = b.key.id,
                type = b.key.type,
                qualifier = b.key.qualifier,
                displayName = b.keyType.displayName,
                kind = NodeKind.INJECTABLE,
                scope = b.scopeName,
                boundTo = b.extraKeys.map { it.id },
                providedBy = providedBy(b.declaration, b.provenance),
            )
            // Satellite nodes for the interfaces an implementation is bound to.
            b.extraKeys.forEachIndexed { i, extra ->
                nodes.getOrPut(extra.id) {
                    GraphNode(
                        id = extra.id,
                        type = extra.type,
                        qualifier = extra.qualifier,
                        displayName = b.extraKeyTypes.getOrNull(i)?.displayName
                            ?: extra.type.substringAfterLast('.'),
                        kind = NodeKind.BOUND_INTERFACE,
                        scope = b.scopeName,
                        providedBy = providedBy(b.declaration, b.provenance),
                    )
                }
            }
            for (d in b.dependencies) {
                addEdge(b.key.id, d.key, d.siteKind, d.paramName, d.deferred, SiteRef(d.site.filePath, d.site.line))
            }
        }

        // Inferred Set<I> multibindings: one aggregate node, edges to every implementation.
        for (set in scan.setBindings) {
            nodes[set.key.id] = GraphNode(
                id = set.key.id,
                type = set.key.type,
                qualifier = set.key.qualifier,
                displayName = "Set<${set.elementType.displayName}>",
                kind = NodeKind.SET,
            )
            for (elementKey in set.elementKeys) {
                addEdge(
                    set.key.id, elementKey, SiteKind.SET_CONTRIBUTION, null,
                    com.kite.di.graph.DeferredKind.NONE,
                    SiteRef(set.provenance.filePath, set.provenance.line),
                )
            }
        }

        // ViewModels: entry points with generated adapters — consumers, not bindings.
        for (vm in scan.viewModels) {
            nodes[vm.targetType.fqn] = GraphNode(
                id = vm.targetType.fqn,
                type = vm.targetType.fqn,
                displayName = vm.targetType.displayName,
                kind = NodeKind.ENTRY_POINT,
                providedBy = providedBy(vm.targetType.displayName, vm.provenance),
            )
            for (d in vm.dependencies) {
                addEdge(vm.targetType.fqn, d.key, d.siteKind, d.paramName, d.deferred, SiteRef(d.site.filePath, d.site.line))
            }
        }

        // Dangling targets (built-ins, graph arguments) become external nodes.
        for (edge in edges) {
            nodes.getOrPut(edge.to) {
                val key = Key.parse(edge.to)
                GraphNode(
                    id = edge.to,
                    type = key.type,
                    qualifier = key.qualifier,
                    displayName = key.qualifier?.let { "$it: ${key.type.substringAfterLast('.')}" }
                        ?: key.type.substringAfterLast('.'),
                    kind = NodeKind.EXTERNAL,
                )
            }
        }

        return GraphSnapshot(
            appId = appId,
            variant = variant,
            scopes = scan.scopes.sortedBy { it.level },
            nodes = nodes.values.sortedBy { it.id },
            edges = edges.sortedBy { it.id },
        )
    }

    private fun providedBy(declaration: String, provenance: Provenance): ProvidedBy = ProvidedBy(
        declaration = declaration,
        gradleModule = provenance.gradleModule,
        file = provenance.filePath,
        line = provenance.line,
    )
}
