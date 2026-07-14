package com.kite.di.processor.export

import com.kite.di.graph.GraphEdge
import com.kite.di.graph.GraphNode
import com.kite.di.graph.GraphSnapshot
import com.kite.di.graph.Key
import com.kite.di.graph.NodeKind
import com.kite.di.graph.ProvidedBy
import com.kite.di.graph.SiteKind
import com.kite.di.graph.SiteRef
import com.kite.di.processor.model.BindingDeclKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.ScanResult

/**
 * Model → [GraphSnapshot]. Output is deterministic (nodes/edges sorted by id, no
 * timestamp) so `graph.json` diffs cleanly in CI. The inspector stamps
 * `generatedAt` and the `runtime` section at serve time.
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

        // Set<T> multibindings: one aggregate node per set key, one satellite node
        // per @IntoSet contribution (contribution ids embed the declaration so two
        // contributions of the same element type stay distinct and stable).
        for ((setKey, contributions) in scan.bindings.filter { it.intoSet }.groupBy { it.setKey!! }) {
            nodes[setKey.id] = GraphNode(
                id = setKey.id,
                type = setKey.type,
                qualifier = setKey.qualifier,
                displayName = "Set<${contributions.first().keyType.displayName}>",
                kind = NodeKind.SET,
            )
            for (c in contributions) {
                val contribId = "${setKey.id}#${c.declaration}"
                nodes[contribId] = GraphNode(
                    id = contribId,
                    type = c.key.type,
                    qualifier = c.key.qualifier,
                    displayName = c.declaration,
                    kind = NodeKind.PROVIDES,
                    providedBy = providedBy(c),
                )
                addEdge(
                    setKey.id, Key(contribId), SiteKind.SET_CONTRIBUTION, null,
                    com.kite.di.graph.DeferredKind.NONE,
                    SiteRef(c.provenance.filePath, c.provenance.line),
                )
                for (d in c.dependencies) {
                    addEdge(contribId, d.key, d.siteKind, d.paramName, d.deferred, SiteRef(d.site.filePath, d.site.line))
                }
            }
        }

        for (b in scan.bindings.filter { !it.intoSet }) {
            nodes[b.key.id] = GraphNode(
                id = b.key.id,
                type = b.key.type,
                qualifier = b.key.qualifier,
                displayName = b.keyType.displayName,
                kind = if (b.declKind == BindingDeclKind.INJECTABLE) NodeKind.INJECTABLE else NodeKind.PROVIDES,
                scope = b.scopeName,
                boundTo = b.extraKeys.map { it.id },
                providedBy = providedBy(b),
            )
            // Satellite nodes for interfaces that exist only via bindTo.
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
                        providedBy = providedBy(b),
                    )
                }
            }
            for (d in b.dependencies) {
                addEdge(b.key.id, d.key, d.siteKind, d.paramName, d.deferred, SiteRef(d.site.filePath, d.site.line))
            }
        }

        for (m in scan.memberInjects) {
            nodes.getOrPut(m.targetType.fqn) {
                GraphNode(
                    id = m.targetType.fqn,
                    type = m.targetType.fqn,
                    displayName = m.targetType.displayName,
                    kind = NodeKind.ENTRY_POINT,
                    providedBy = ProvidedBy(
                        declaration = m.targetType.displayName,
                        gradleModule = m.provenance.gradleModule,
                        file = m.provenance.filePath,
                        line = m.provenance.line,
                    ),
                )
            }
            for (f in m.fields) {
                addEdge(m.targetType.fqn, f.key, SiteKind.FIELD, f.fieldName, f.deferred, SiteRef(f.site.filePath, f.site.line))
            }
        }

        // Dangling targets (built-ins like Application/Context) become external nodes.
        for (edge in edges) {
            nodes.getOrPut(edge.to) {
                val key = Key.parse(edge.to)
                GraphNode(
                    id = edge.to,
                    type = key.type,
                    qualifier = key.qualifier,
                    displayName = key.type.substringAfterLast('.'),
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

    private fun providedBy(b: BindingModel): ProvidedBy = ProvidedBy(
        declaration = b.declaration,
        gradleModule = b.provenance.gradleModule,
        file = b.provenance.filePath,
        line = b.provenance.line,
    )
}
