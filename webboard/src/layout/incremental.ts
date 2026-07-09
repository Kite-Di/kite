/**
 * Layout controller:
 *  - full elkjs relayout in the worker, positions applied with 400 ms
 *    interpolation so the mental map survives
 *  - incremental strategy for small patches (≤ 15 % of nodes changed): new
 *    nodes are seeded at their neighbors' barycenter, untouched nodes do not
 *    move (stability beats optimality while the developer is watching)
 *  - pinned nodes (drag = pin, persisted) are never moved by layout
 */

import type { GraphEdge } from '../model/graph';
import type { LayoutRequest, LayoutResponse } from './elk.worker';

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

export class LayoutController {
  private worker: Worker;
  private nextRequestId = 1;
  private pending = new Map<number, (res: LayoutResponse) => void>();

  constructor() {
    this.worker = new Worker(new URL('./elk.worker.ts', import.meta.url), { type: 'module' });
    this.worker.onmessage = (ev: MessageEvent<LayoutResponse>) => {
      const resolve = this.pending.get(ev.data.requestId);
      if (resolve) {
        this.pending.delete(ev.data.requestId);
        resolve(ev.data);
      }
    };
  }

  /**
   * Full layered layout. `pins` positions win over computed ones — pinned
   * nodes are excluded from the result so callers leave them in place.
   */
  async fullLayout(
    nodes: NodeBox[],
    edges: GraphEdge[],
    pins: Record<string, Position>,
  ): Promise<Map<string, Position>> {
    const requestId = this.nextRequestId++;
    const req: LayoutRequest = {
      requestId,
      nodes: nodes.map((n) => ({ id: n.id, width: n.w, height: n.h })),
      // provider (edge.to = dependency) ranks left; consumer (edge.from) right
      edges: edges.map((e) => ({ id: e.id, source: e.to, target: e.from })),
    };
    const res = await new Promise<LayoutResponse>((resolve) => {
      this.pending.set(requestId, resolve);
      this.worker.postMessage(req);
    });
    if (res.error) throw new Error(`elk layout failed: ${res.error}`);
    const out = new Map<string, Position>();
    for (const [id, pos] of Object.entries(res.positions)) {
      out.set(id, pins[id] ?? pos);
    }
    return out;
  }

  dispose(): void {
    this.worker.terminate();
    this.pending.clear();
  }
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
