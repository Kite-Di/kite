/**
 * Snapshot diff by stable node/edge ids — the same algorithm
 * as GraphDiff.kt on the Kotlin side.
 *
 * Op emission order (removals before additions so animations and incremental
 * layout can process the list front-to-back):
 *   removeEdge → removeNode → addNode → updateNode → addEdge
 */

import type { GraphEdge, GraphNode, GraphSnapshot, PatchOp, Provenance } from './graph';

function provenanceEquals(a: Provenance | null | undefined, b: Provenance | null | undefined): boolean {
  if (a == null || b == null) return a == null && b == null;
  return a.declaration === b.declaration && a.gradleModule === b.gradleModule && a.file === b.file && a.line === b.line;
}

function boundToEquals(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const sa = [...a].sort();
  const sb = [...b].sort();
  return sa.every((v, i) => v === sb[i]);
}

/**
 * A node counts as "updated" when scope, kind, boundTo or providedBy changed
 *. Identity fields (id/type/qualifier/displayName) are part of
 * the stable id and cannot change without producing a remove+add pair.
 */
export function nodeChanged(a: GraphNode, b: GraphNode): boolean {
  return (
    (a.scope ?? null) !== (b.scope ?? null) ||
    a.kind !== b.kind ||
    !boundToEquals(a.boundTo, b.boundTo) ||
    !provenanceEquals(a.providedBy, b.providedBy)
  );
}

/** Diff two snapshots into an ordered PatchOp list. */
export function diffSnapshots(prev: GraphSnapshot, next: GraphSnapshot): PatchOp[] {
  const prevNodes = new Map(prev.nodes.map((n) => [n.id, n]));
  const nextNodes = new Map(next.nodes.map((n) => [n.id, n]));
  const prevEdges = new Map(prev.edges.map((e) => [e.id, e]));
  const nextEdges = new Map(next.edges.map((e) => [e.id, e]));

  const ops: PatchOp[] = [];

  // 1. removeEdge
  for (const [id] of prevEdges) {
    if (!nextEdges.has(id)) ops.push({ op: 'removeEdge', edgeId: id });
  }
  // 2. removeNode
  for (const [id] of prevNodes) {
    if (!nextNodes.has(id)) ops.push({ op: 'removeNode', nodeId: id });
  }
  // 3. addNode
  for (const [id, node] of nextNodes) {
    if (!prevNodes.has(id)) ops.push({ op: 'addNode', node });
  }
  // 4. updateNode
  for (const [id, node] of nextNodes) {
    const before = prevNodes.get(id);
    if (before && nodeChanged(before, node)) ops.push({ op: 'updateNode', node });
  }
  // 5. addEdge
  for (const [id, edge] of nextEdges) {
    if (!prevEdges.has(id)) ops.push({ op: 'addEdge', edge });
  }
  return ops;
}

/**
 * Applies a patch to a snapshot, returning a new snapshot (input untouched).
 * Node/edge order of survivors is preserved; additions are appended.
 */
export function applyPatch(snapshot: GraphSnapshot, ops: PatchOp[]): GraphSnapshot {
  let nodes: GraphNode[] = [...snapshot.nodes];
  let edges: GraphEdge[] = [...snapshot.edges];

  for (const op of ops) {
    switch (op.op) {
      case 'removeEdge':
        edges = edges.filter((e) => e.id !== op.edgeId);
        break;
      case 'removeNode':
        nodes = nodes.filter((n) => n.id !== op.nodeId);
        break;
      case 'addNode':
        nodes.push(op.node);
        break;
      case 'updateNode':
        nodes = nodes.map((n) => (n.id === op.node.id ? op.node : n));
        break;
      case 'addEdge':
        edges.push(op.edge);
        break;
    }
  }
  return { ...snapshot, nodes, edges };
}

export interface DiffSummary {
  addedNodes: number;
  removedNodes: number;
  updatedNodes: number;
  addedEdges: number;
  removedEdges: number;
  /** Ids of every node touched by the diff (for camera framing). */
  touchedNodeIds: string[];
}

/** Aggregates a PatchOp list for toast summaries ("+2 nodes · +3 edges · 1 changed"). */
export function summarizeOps(ops: PatchOp[]): DiffSummary {
  const s: DiffSummary = {
    addedNodes: 0,
    removedNodes: 0,
    updatedNodes: 0,
    addedEdges: 0,
    removedEdges: 0,
    touchedNodeIds: [],
  };
  for (const op of ops) {
    switch (op.op) {
      case 'addNode':
        s.addedNodes++;
        s.touchedNodeIds.push(op.node.id);
        break;
      case 'removeNode':
        s.removedNodes++;
        break;
      case 'updateNode':
        s.updatedNodes++;
        s.touchedNodeIds.push(op.node.id);
        break;
      case 'addEdge':
        s.addedEdges++;
        s.touchedNodeIds.push(op.edge.from, op.edge.to);
        break;
      case 'removeEdge':
        s.removedEdges++;
        break;
    }
  }
  s.touchedNodeIds = [...new Set(s.touchedNodeIds)];
  return s;
}
