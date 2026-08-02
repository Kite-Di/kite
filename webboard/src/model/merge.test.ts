import { describe, expect, it } from 'vitest';
import type { GraphEdge, GraphNode, GraphSnapshot } from './graph';
import { mergeSnapshots } from './merge';

function node(id: string, kind: GraphNode['kind'], extra: Partial<GraphNode> = {}): GraphNode {
  return { id, type: id, displayName: id.split('.').pop() ?? id, kind, boundTo: [], ...extra };
}

function edge(from: string, to: string): GraphEdge {
  return { id: `${from} -> ${to} # 0`, from, to, siteKind: 'constructorParam', deferred: 'none' };
}

function snap(partial: Partial<GraphSnapshot>): GraphSnapshot {
  return {
    schemaVersion: 2,
    appId: 'com.example.app',
    variant: 'debug',
    scopes: [],
    nodes: [],
    edges: [],
    ...partial,
  };
}

describe('mergeSnapshots (multi-module fragments)', () => {
  it('replaces a consumer fragment external placeholder with the owning module node', () => {
    // :app consumed core.UserRepository — its fragment only knows it as external.
    const app = snap({
      nodes: [node('app.Presenter', 'injectable'), node('core.UserRepository', 'external')],
      edges: [edge('app.Presenter', 'core.UserRepository')],
    });
    // :core owns the interface — a boundInterface satellite of the implementation.
    const core = snap({
      appId: 'unknown',
      nodes: [
        node('core.NetworkUserRepository', 'injectable', { boundTo: ['core.UserRepository'] }),
        node('core.UserRepository', 'boundInterface'),
      ],
    });

    const merged = mergeSnapshots(app, core === undefined ? [] : [core]);
    expect(merged.nodes.find((n) => n.id === 'core.UserRepository')?.kind).toBe('boundInterface');
    expect(merged.nodes).toHaveLength(3);
    expect(merged.appId).toBe('com.example.app'); // primary wins
  });

  it('keeps the real node no matter the fragment order', () => {
    const withReal = snap({ nodes: [node('core.Analytics', 'injectable')] });
    const withPlaceholder = snap({ nodes: [node('core.Analytics', 'external')] });
    for (const fragments of [[withReal, withPlaceholder], [withPlaceholder, withReal]]) {
      const merged = mergeSnapshots(snap({}), fragments);
      expect(merged.nodes).toHaveLength(1);
      expect(merged.nodes[0]!.kind).toBe('injectable');
    }
  });

  it('unions edges and scopes without duplicates, sorted', () => {
    const a = snap({
      scopes: [{ name: 'Singleton', level: 0 }, { name: 'Session', level: 10 }],
      nodes: [node('a.A', 'injectable'), node('b.B', 'external')],
      edges: [edge('a.A', 'b.B')],
    });
    const b = snap({
      scopes: [{ name: 'Singleton', level: 0 }, { name: 'ActivityScoped', level: 1 }],
      nodes: [node('b.B', 'injectable')],
      edges: [edge('a.A', 'b.B')],
    });
    const merged = mergeSnapshots(a, [b]);
    expect(merged.scopes.map((s) => s.name)).toEqual(['Singleton', 'ActivityScoped', 'Session']);
    expect(merged.edges).toHaveLength(1);
    expect(merged.nodes.map((n) => n.id)).toEqual(['a.A', 'b.B']);
  });

  it('is the identity for a single-module app', () => {
    const app = snap({
      nodes: [node('a.A', 'injectable')],
      edges: [],
      scopes: [{ name: 'Singleton', level: 0 }],
    });
    expect(mergeSnapshots(app, [])).toEqual(app);
  });
});
