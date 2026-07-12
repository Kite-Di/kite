/**
 * Incremental placement helpers:
 *  - full relayout lives in ./layered.ts (deterministic tree layout)
 *  - incremental strategy for small patches (≤ 15 % of nodes changed): new
 *    nodes are seeded at their neighbors' barycenter, untouched nodes do not
 *    move (stability beats optimality while the developer is watching)
 *  - pinned nodes (drag = pin, persisted) are never moved by layout
 */

import type { GraphEdge } from '../model/graph';

export interface NodeBox {
  id: string;
  w: number;
  h: number;
}

export interface Position {
  x: number;
  y: number;
}

/** Share of changed nodes below which we keep the existing layout untouched. */
export const INCREMENTAL_THRESHOLD = 0.15;

export function shouldUseIncremental(changedNodeCount: number, totalNodes: number): boolean {
  if (totalNodes === 0) return false;
  return changedNodeCount / totalNodes <= INCREMENTAL_THRESHOLD;
}

interface PlacedBox extends NodeBox, Position {}

/**
 * Barycenter seeding for incremental placement: each new node lands at the
 * average position of its already-placed neighbors (nudged to avoid
 * overlaps); nodes with no placed neighbors go to the right of the graph.
 */
export function placeIncremental(
  newNodes: NodeBox[],
  edges: GraphEdge[],
  existing: Map<string, PlacedBox>,
  pins: Record<string, Position>,
): Map<string, Position> {
  const placed = new Map<string, PlacedBox>(existing);
  const result = new Map<string, Position>();

  // adjacency over all edges (both directions count as "neighbor")
  const neighbors = new Map<string, string[]>();
  for (const e of edges) {
    (neighbors.get(e.from) ?? neighbors.set(e.from, []).get(e.from)!).push(e.to);
    (neighbors.get(e.to) ?? neighbors.set(e.to, []).get(e.to)!).push(e.from);
  }

  let fallbackY = 0;
  const bounds = boundsOf([...placed.values()]);

  for (const node of newNodes) {
    const pin = pins[node.id];
    if (pin) {
      result.set(node.id, pin);
      placed.set(node.id, { ...node, ...pin });
      continue;
    }

    const near = (neighbors.get(node.id) ?? [])
      .map((id) => placed.get(id))
      .filter((p): p is PlacedBox => p !== undefined);

    let x: number;
    let y: number;
    if (near.length > 0) {
      x = near.reduce((s, p) => s + p.x + p.w / 2, 0) / near.length - node.w / 2;
      y = near.reduce((s, p) => s + p.y + p.h / 2, 0) / near.length - node.h / 2;
      // offset slightly below the barycenter so it doesn't sit on an edge path
      y += 40;
    } else if (bounds) {
      x = bounds.maxX + 120;
      y = (bounds.minY + bounds.maxY) / 2 + fallbackY;
      fallbackY += node.h + 24;
    } else {
      x = 0;
      y = fallbackY;
      fallbackY += node.h + 24;
    }

    // collision nudge: shift down until free
    const pad = 18;
    let guard = 0;
    while (guard++ < 200 && overlapsAny(x, y, node, placed, pad)) {
      y += node.h + pad;
    }

    result.set(node.id, { x, y });
    placed.set(node.id, { ...node, x, y });
  }
  return result;
}

function overlapsAny(x: number, y: number, node: NodeBox, placed: Map<string, PlacedBox>, pad: number): boolean {
  for (const p of placed.values()) {
    if (p.id === node.id) continue;
    if (x < p.x + p.w + pad && x + node.w + pad > p.x && y < p.y + p.h + pad && y + node.h + pad > p.y) {
      return true;
    }
  }
  return false;
}

function boundsOf(boxes: PlacedBox[]): { minX: number; minY: number; maxX: number; maxY: number } | null {
  if (boxes.length === 0) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const b of boxes) {
    minX = Math.min(minX, b.x);
    minY = Math.min(minY, b.y);
    maxX = Math.max(maxX, b.x + b.w);
    maxY = Math.max(maxY, b.y + b.h);
  }
  return { minX, minY, maxX, maxY };
}
