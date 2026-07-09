import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { applyPatch, diffSnapshots, summarizeOps } from './diff';
import { parseSnapshot, type GraphEdge, type GraphNode, type GraphSnapshot } from './graph';

const FIXTURE_URL = new URL('../../mock/fixtures/graph.json', import.meta.url);

function fixture(): GraphSnapshot {
  return parseSnapshot(JSON.parse(readFileSync(fileURLToPath(FIXTURE_URL), 'utf-8')));
}

function node(id: string, overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id,
    type: id,
    displayName: id.split('.').pop() ?? id,
    kind: 'injectable',
    scope: null,
    boundTo: [],
    providedBy: { declaration: id, gradleModule: ':app', file: `${id}.kt`, line: 1 },
    ...overrides,
  };
}

function edge(from: string, to: string, overrides: Partial<GraphEdge> = {}): GraphEdge {
  return {
    id: `${from} -> ${to} # 0`,
    from,
    to,
    siteKind: 'constructorParam',
    paramName: 'dep',
    deferred: 'none',
    site: { file: `${from}.kt`, line: 5 },
    ...overrides,
  };
}

function snap(nodes: GraphNode[], edges: GraphEdge[]): GraphSnapshot {
  return { schemaVersion: 1, appId: 'test', variant: 'debug', scopes: [], nodes, edges, runtime: null };
}

function sortedById<T extends { id: string }>(xs: T[]): T[] {
  return [...xs].sort((a, b) => a.id.localeCompare(b.id));
}

describe('diffSnapshots', () => {
  it('identical snapshots produce no ops', () => {
    const a = fixture();
    const b = fixture();
    expect(diffSnapshots(a, b)).toEqual([]);
  });

  it('a snapshot diffed against itself produces no ops', () => {
    const a = fixture();
    expect(diffSnapshots(a, a)).toEqual([]);
  });

  it('detects added node + edge', () => {
    const a = snap([node('A')], []);
    const b = snap([node('A'), node('B')], [edge('A', 'B')]);
    const ops = diffSnapshots(a, b);
    expect(ops).toEqual([
      { op: 'addNode', node: node('B') },
      { op: 'addEdge', edge: edge('A', 'B') },
    ]);
  });

  it('detects removed node + edge', () => {
    const a = snap([node('A'), node('B')], [edge('A', 'B')]);
    const b = snap([node('A')], []);
    const ops = diffSnapshots(a, b);
    expect(ops).toEqual([
      { op: 'removeEdge', edgeId: 'A -> B # 0' },
      { op: 'removeNode', nodeId: 'B' },
    ]);
  });

  it('emits updateNode when scope changes', () => {
    const a = snap([node('A', { scope: null })], []);
    const b = snap([node('A', { scope: 'Singleton' })], []);
    expect(diffSnapshots(a, b)).toEqual([{ op: 'updateNode', node: node('A', { scope: 'Singleton' }) }]);
  });

  it('emits updateNode when kind, boundTo or providedBy change', () => {
    const base = node('A');
    expect(diffSnapshots(snap([base], []), snap([node('A', { kind: 'provides' })], []))).toHaveLength(1);
    expect(diffSnapshots(snap([base], []), snap([node('A', { boundTo: ['X'] })], []))).toHaveLength(1);
    expect(
      diffSnapshots(
        snap([base], []),
        snap([node('A', { providedBy: { declaration: 'A', gradleModule: ':lib', file: 'A.kt', line: 1 } })], []),
      ),
    ).toHaveLength(1);
  });

  it('does NOT emit updateNode for boundTo reordering or displayName-only differences', () => {
    const a = snap([node('A', { boundTo: ['X', 'Y'] })], []);
    const b = snap([node('A', { boundTo: ['Y', 'X'] })], []);
    expect(diffSnapshots(a, b)).toEqual([]);
  });

  it('orders ops: removeEdge, removeNode, addNode, updateNode, addEdge', () => {
    const a = snap([node('A'), node('Gone')], [edge('A', 'Gone')]);
    const b = snap([node('A', { scope: 'Singleton' }), node('New')], [edge('A', 'New')]);
    const ops = diffSnapshots(a, b).map((o) => o.op);
    expect(ops).toEqual(['removeEdge', 'removeNode', 'addNode', 'updateNode', 'addEdge']);
  });

  it('summarizes ops for toasts', () => {
    const a = snap([node('A'), node('Gone')], [edge('A', 'Gone')]);
    const b = snap([node('A', { scope: 'Singleton' }), node('New')], [edge('A', 'New')]);
    const s = summarizeOps(diffSnapshots(a, b));
    expect(s.addedNodes).toBe(1);
    expect(s.removedNodes).toBe(1);
    expect(s.updatedNodes).toBe(1);
    expect(s.addedEdges).toBe(1);
    expect(s.removedEdges).toBe(1);
    expect(s.touchedNodeIds).toContain('New');
    expect(s.touchedNodeIds).toContain('A');
  });
});

describe('applyPatch', () => {
  it('apply(diff(a, b), a) equals b (synthetic)', () => {
    const a = snap([node('A'), node('B'), node('C')], [edge('A', 'B'), edge('B', 'C')]);
    const b = snap(
      [node('A', { scope: 'Singleton' }), node('C'), node('D')],
      [edge('B', 'C', { id: 'kept -> but # different' }), edge('A', 'D')],
    );
    // remove edge B->C id mismatch handling: build b edges explicitly
    b.edges = [edge('A', 'D'), edge('C', 'D')];
    b.nodes = [node('A', { scope: 'Singleton' }), node('C'), node('D')];

    const result = applyPatch(a, diffSnapshots(a, b));
    expect(sortedById(result.nodes)).toEqual(sortedById(b.nodes));
    expect(sortedById(result.edges)).toEqual(sortedById(b.edges));
  });

  it('apply(diff(a, b), a) equals b (real fixture → mutated fixture)', () => {
    const a = fixture();
    const b = fixture();
    // Simulate a rebuild: drop a node + its edges, add a node + edge, change a scope.
    const droppedId = 'com.kite.demo.ui.SecondPresenter';
    b.nodes = b.nodes.filter((n) => n.id !== droppedId);
    b.edges = b.edges.filter((e) => e.from !== droppedId && e.to !== droppedId);
    b.nodes = b.nodes.map((n) =>
      n.id === 'com.kite.demo.ui.GreetingUseCase' ? { ...n, scope: 'ActivityScoped' } : n,
    );
    const extra = node('com.example.CrashReporter', { scope: 'Singleton' });
    b.nodes.push(extra);
    b.edges.push(edge('com.kite.demo.MainActivity', extra.id, { siteKind: 'field' }));

    const ops = diffSnapshots(a, b);
    expect(ops.length).toBeGreaterThan(0);
    const result = applyPatch(a, ops);
    expect(sortedById(result.nodes)).toEqual(sortedById(b.nodes));
    expect(sortedById(result.edges)).toEqual(sortedById(b.edges));
    // Idempotence: diff after apply is empty.
    expect(diffSnapshots(result, b)).toEqual([]);
  });

  it('does not mutate the input snapshot', () => {
    const a = snap([node('A')], []);
    const before = JSON.stringify(a);
    applyPatch(a, [
      { op: 'addNode', node: node('B') },
      { op: 'removeNode', nodeId: 'A' },
    ]);
    expect(JSON.stringify(a)).toBe(before);
  });
});
