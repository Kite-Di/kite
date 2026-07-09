/**
 * Display list + spatial hash: holds the animatable view-model
 * for nodes/edges, answers viewport-culling queries and hit-tests, and owns
 * focus (neighborhood dimming) & filter visibility.
 */

import type { GraphEdge, GraphNode } from '../model/graph';
import type { Point, Rect } from './Camera';

export interface VNode {
  node: GraphNode;
  /** World top-left. */
  x: number;
  y: number;
  w: number;
  h: number;
  alpha: number;
  scale: number;
  pulse: number;
  glow: number;
  shake: number;
  errorHalo: number;
  error: string | null;
  pinned: boolean;
  /** Live instance count (runtime badge). */
  instances: number;
  lastCreatedAt: number | null;
  lastCreationMicros: number | null;
  lastScopeId: string | null;
  removing: boolean;
}

export interface VEdge {
  edge: GraphEdge;
  alpha: number;
  drawProgress: number;
  removing: boolean;
}

export function makeVNode(node: GraphNode, w: number, h: number): VNode {
  return {
    node,
    x: 0,
    y: 0,
    w,
    h,
    alpha: 1,
    scale: 1,
    pulse: 0,
    glow: 0,
    shake: 0,
    errorHalo: 0,
    error: null,
    pinned: false,
    instances: 0,
    lastCreatedAt: null,
    lastCreationMicros: null,
    lastScopeId: null,
    removing: false,
  };
}

export function makeVEdge(edge: GraphEdge): VEdge {
  return { edge, alpha: 1, drawProgress: 1, removing: false };
}

const CELL = 320;
const DIM_ALPHA = 0.15;

export class Scene {
  readonly nodes = new Map<string, VNode>();
  readonly edges = new Map<string, VEdge>();

  /** Selected node id — everything outside its neighborhood dims to 15 %. */
  private focusId: string | null = null;
  private focusNodes: Set<string> | null = null;
  private focusEdges: Set<string> | null = null;

  /** Edge highlighted from the side panel hover. */
  highlightEdgeId: string | null = null;
  hoverNodeId: string | null = null;

  /** Nodes passing the toolbar filters; null = no filter active. */
  private filterVisible: Set<string> | null = null;

  private cells = new Map<string, VNode[]>();
  private indexDirty = true;

  // ---- population ----

  addNode(vn: VNode): void {
    this.nodes.set(vn.node.id, vn);
    this.indexDirty = true;
  }

  removeNode(id: string): void {
    this.nodes.delete(id);
    this.indexDirty = true;
    if (this.focusId === id) this.setFocus(null);
  }

  addEdge(ve: VEdge): void {
    this.edges.set(ve.edge.id, ve);
  }

  removeEdge(id: string): void {
    this.edges.delete(id);
  }

  clear(): void {
    this.nodes.clear();
    this.edges.clear();
    this.cells.clear();
    this.focusId = null;
    this.focusNodes = null;
    this.focusEdges = null;
    this.filterVisible = null;
    this.highlightEdgeId = null;
    this.hoverNodeId = null;
    this.indexDirty = true;
  }

  markDirty(): void {
    this.indexDirty = true;
  }

  // ---- spatial hash ----

  private rebuildIndex(): void {
    this.cells.clear();
    for (const vn of this.nodes.values()) {
      const x0 = Math.floor(vn.x / CELL);
      const y0 = Math.floor(vn.y / CELL);
      const x1 = Math.floor((vn.x + vn.w) / CELL);
      const y1 = Math.floor((vn.y + vn.h) / CELL);
      for (let cx = x0; cx <= x1; cx++) {
        for (let cy = y0; cy <= y1; cy++) {
          const k = `${cx},${cy}`;
          let bucket = this.cells.get(k);
          if (!bucket) {
            bucket = [];
            this.cells.set(k, bucket);
          }
          bucket.push(vn);
        }
      }
    }
    this.indexDirty = false;
  }

  /** Nodes intersecting a world rect (viewport culling). */
  query(rect: Rect): VNode[] {
    if (this.indexDirty) this.rebuildIndex();
    const out: VNode[] = [];
    const seen = new Set<string>();
    const x0 = Math.floor(rect.x / CELL);
    const y0 = Math.floor(rect.y / CELL);
    const x1 = Math.floor((rect.x + rect.w) / CELL);
    const y1 = Math.floor((rect.y + rect.h) / CELL);
    for (let cx = x0; cx <= x1; cx++) {
      for (let cy = y0; cy <= y1; cy++) {
        const bucket = this.cells.get(`${cx},${cy}`);
        if (!bucket) continue;
        for (const vn of bucket) {
          if (seen.has(vn.node.id)) continue;
          seen.add(vn.node.id);
          if (vn.x + vn.w >= rect.x && vn.x <= rect.x + rect.w && vn.y + vn.h >= rect.y && vn.y <= rect.y + rect.h) {
            out.push(vn);
          }
        }
      }
    }
    return out;
  }

  /** Topmost node containing a world point (spatial hash → precise rect test). */
  hitTest(p: Point): VNode | null {
    if (this.indexDirty) this.rebuildIndex();
    const bucket = this.cells.get(`${Math.floor(p.x / CELL)},${Math.floor(p.y / CELL)}`);
    if (!bucket) return null;
    for (let i = bucket.length - 1; i >= 0; i--) {
      const vn = bucket[i]!;
      if (vn.alpha <= 0.02 || vn.removing) continue;
      if (p.x >= vn.x && p.x <= vn.x + vn.w && p.y >= vn.y && p.y <= vn.y + vn.h) return vn;
    }
    return null;
  }

  bounds(): Rect | null {
    return this.boundsOf([...this.nodes.keys()]);
  }

  boundsOf(ids: Iterable<string>): Rect | null {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    let any = false;
    for (const id of ids) {
      const vn = this.nodes.get(id);
      if (!vn) continue;
      any = true;
      minX = Math.min(minX, vn.x);
      minY = Math.min(minY, vn.y);
      maxX = Math.max(maxX, vn.x + vn.w);
      maxY = Math.max(maxY, vn.y + vn.h);
    }
    return any ? { x: minX, y: minY, w: maxX - minX, h: maxY - minY } : null;
  }

  // ---- focus (selection neighborhood) ----

  setFocus(id: string | null): void {
    this.focusId = id;
    if (id === null || !this.nodes.has(id)) {
      this.focusId = null;
      this.focusNodes = null;
      this.focusEdges = null;
      return;
    }
    const nodes = new Set<string>([id]);
    const edges = new Set<string>();
    for (const ve of this.edges.values()) {
      if (ve.edge.from === id || ve.edge.to === id) {
        edges.add(ve.edge.id);
        nodes.add(ve.edge.from);
        nodes.add(ve.edge.to);
      }
    }
    this.focusNodes = nodes;
    this.focusEdges = edges;
  }

  get selectedId(): string | null {
    return this.focusId;
  }

  /** Recomputes the neighborhood (after graph changes). */
  refreshFocus(): void {
    this.setFocus(this.focusId);
  }

  // ---- filters ----

  setFilterVisible(visible: Set<string> | null): void {
    this.filterVisible = visible;
  }

  get hasFilter(): boolean {
    return this.filterVisible !== null;
  }

  passesFilter(id: string): boolean {
    return this.filterVisible === null || this.filterVisible.has(id);
  }

  // ---- effective alphas (focus dim × filter dim × animation alpha) ----

  nodeDimFactor(id: string): number {
    let f = 1;
    if (this.focusNodes && !this.focusNodes.has(id)) f = DIM_ALPHA;
    if (!this.passesFilter(id)) f = Math.min(f, 0.08);
    return f;
  }

  edgeDimFactor(ve: VEdge): number {
    let f = 1;
    if (this.focusEdges && !this.focusEdges.has(ve.edge.id)) f = DIM_ALPHA;
    if (!this.passesFilter(ve.edge.from) || !this.passesFilter(ve.edge.to)) f = Math.min(f, 0.06);
    return f;
  }
}
