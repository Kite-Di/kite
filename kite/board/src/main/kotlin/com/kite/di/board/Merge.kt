package com.kite.di.board

import com.kite.di.graph.GraphEdge
import com.kite.di.graph.GraphNode
import com.kite.di.graph.GraphSnapshot
import com.kite.di.graph.NodeKind
import com.kite.di.graph.ScopeDef

/**
 * Multi-module graphs: every Gradle module's KSP run writes its own graph
 * *fragment* (`<module>/build/kite/graph.json`), and the board merges them into
 * the one canvas it renders.
 *
 * Merge rules:
 *  - nodes unite by id; a fragment's real node (any kind) replaces another
 *    fragment's `external` placeholder for the same id — the consumer module
 *    exports a cross-module dependency as a dangling external node, while the
 *    owning module knows what it actually is;
 *  - edges and scopes unite by id / name (cross-module scope-level collisions are
 *    a build error long before a fragment reaches the board);
 *  - appId/variant/runtime come from the primary (application) fragment;
 *  - output is sorted, so merging is deterministic.
 */
fun mergeSnapshots(primary: GraphSnapshot, fragments: List<GraphSnapshot>): GraphSnapshot {
    val scopes = LinkedHashMap<String, ScopeDef>()
    val nodes = LinkedHashMap<String, GraphNode>()
    val edges = LinkedHashMap<String, GraphEdge>()

    for (snapshot in listOf(primary) + fragments) {
        for (scope in snapshot.scopes) scopes.putIfAbsent(scope.name, scope)
        for (node in snapshot.nodes) {
            val existing = nodes[node.id]
            if (existing == null || (existing.kind == NodeKind.EXTERNAL && node.kind != NodeKind.EXTERNAL)) {
                nodes[node.id] = node
            }
        }
        for (edge in snapshot.edges) edges.putIfAbsent(edge.id, edge)
    }

    return primary.copy(
        scopes = scopes.values.sortedWith(compareBy({ it.level }, { it.name })),
        nodes = nodes.values.sortedBy { it.id },
        edges = edges.values.sortedBy { it.id },
    )
}
