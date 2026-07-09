/**
 * elkjs `layered` (Sugiyama) layout in a Web Worker — the main
 * thread never blocks. Direction RIGHT so providers rank left and consumers
 * right. Edges are fed provider→consumer for correct ranking.
 */

import ELK from 'elkjs/lib/elk.bundled.js';
import type { ElkNode } from 'elkjs/lib/elk.bundled.js';

export interface LayoutRequestNode {
  id: string;
  width: number;
  height: number;
}

export interface LayoutRequestEdge {
  id: string;
  /** Provider / dependency node id (rank source, drawn left). */
  source: string;
  /** Consumer node id (rank target, drawn right). */
  target: string;
}

export interface LayoutRequest {
  requestId: number;
  nodes: LayoutRequestNode[];
  edges: LayoutRequestEdge[];
}

export interface LayoutResponse {
  requestId: number;
  positions: Record<string, { x: number; y: number }>;
  error?: string;
}

const elk = new ELK();

const LAYOUT_OPTIONS: Record<string, string> = {
  'elk.algorithm': 'layered',
  'elk.direction': 'RIGHT',
  'elk.layered.spacing.nodeNodeBetweenLayers': '110',
  'elk.spacing.nodeNode': '32',
  'elk.spacing.edgeNode': '24',
  'elk.layered.spacing.edgeNodeBetweenLayers': '30',
  'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
  'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
  'elk.layered.cycleBreaking.strategy': 'GREEDY',
  'elk.edgeRouting': 'SPLINES',
};

self.onmessage = (ev: MessageEvent<LayoutRequest>) => {
  const req = ev.data;
  const nodeIds = new Set(req.nodes.map((n) => n.id));
  const graph: ElkNode = {
    id: 'root',
    layoutOptions: LAYOUT_OPTIONS,
    children: req.nodes.map((n) => ({ id: n.id, width: n.width, height: n.height })),
    edges: req.edges
      .filter((e) => nodeIds.has(e.source) && nodeIds.has(e.target) && e.source !== e.target)
      .map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };

  elk
    .layout(graph)
    .then((res) => {
      const positions: Record<string, { x: number; y: number }> = {};
      for (const child of res.children ?? []) {
        positions[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
      }
      const msg: LayoutResponse = { requestId: req.requestId, positions };
      self.postMessage(msg);
    })
    .catch((err: unknown) => {
      const msg: LayoutResponse = {
        requestId: req.requestId,
        positions: {},
        error: err instanceof Error ? err.message : String(err),
      };
      self.postMessage(msg);
    });
};
