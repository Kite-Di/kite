/**
 * Multi-module graphs: every Gradle module's KSP run writes its own graph
 * *fragment* (`<module>/build/kite/graph.json`), and the board server merges
 * them into the one canvas the board renders.
 *
 * Merge rules:
 *  - nodes unite by id; a fragment's real node (any kind) replaces another
 *    fragment's `external` placeholder for the same id — the consumer module
 *    exports a cross-module dependency as a dangling external node, while the
 *    owning module knows what it actually is;
 *  - edges and scopes unite by id / name (cross-module scope-level collisions
 *    are a build error before a fragment ever reaches the board);
 *  - appId/variant/runtime come from the primary (application) fragment;
 *  - output is sorted, so merging is deterministic.
 */

import type { GraphEdge, GraphNode, GraphSnapshot, ScopeDef } from './graph.ts';

export function mergeSnapshots(primary: GraphSnapshot, fragments: GraphSnapshot[]): GraphSnapshot {
  const scopes = new Map<string, ScopeDef>();
  const nodes = new Map<string, GraphNode>();
  const edges = new Map<string, GraphEdge>();

  for (const snap of [primary, ...fragments]) {
    for (const scope of snap.scopes) {
      if (!scopes.has(scope.name)) scopes.set(scope.name, scope);
    }
    for (const node of snap.nodes) {
      const existing = nodes.get(node.id);
      if (!existing || (existing.kind === 'external' && node.kind !== 'external')) {
        nodes.set(node.id, node);
      }
    }
    for (const edge of snap.edges) {
      if (!edges.has(edge.id)) edges.set(edge.id, edge);
    }
  }

  return {
    ...primary,
    scopes: [...scopes.values()].sort((a, b) => a.level - b.level || a.name.localeCompare(b.name)),
    nodes: [...nodes.values()].sort((a, b) => a.id.localeCompare(b.id)),
    edges: [...edges.values()].sort((a, b) => a.id.localeCompare(b.id)),
  };
}
