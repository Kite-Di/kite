import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { layeredLayout, type EdgeRef, type LayoutBox } from './layered';
import type { GraphSnapshot } from '../model/graph';

const CARD = { w: 200, h: 64 };

function box(id: string): LayoutBox {
  return { id, ...CARD };
}

function edge(from: string, to: string): EdgeRef {
  return { from, to };
}

function overlaps(a: { x: number; y: number }, b: { x: number; y: number }): boolean {
  return a.x < b.x + CARD.w && a.x + CARD.w > b.x && a.y < b.y + CARD.h && a.y + CARD.h > b.y;
}

function assertNoOverlaps(positions: Map<string, { x: number; y: number }>): void {
  const entries = [...positions.entries()];
  for (let i = 0; i < entries.length; i++) {
    for (let j = i + 1; j < entries.length; j++) {
      const [idA, a] = entries[i]!;
      const [idB, b] = entries[j]!;
      expect(overlaps(a, b), `${idA} overlaps ${idB}`).toBe(false);
    }
  }
}

describe('layeredLayout', () => {
  it('places dependencies strictly left of their consumers (tree ranks)', () => {
    // presenter -> useCase -> repo -> api ; presenter -> session
    const nodes = ['api', 'repo', 'useCase', 'presenter', 'session'].map(box);
    const edges = [
      edge('repo', 'api'),
      edge('useCase', 'repo'),
      edge('presenter', 'useCase'),
      edge('presenter', 'session'),
    ];
    const pos = layeredLayout(nodes, edges);
    expect(pos.size).toBe(5);
    expect(pos.get('api')!.x).toBeLessThan(pos.get('repo')!.x);
    expect(pos.get('repo')!.x).toBeLessThan(pos.get('useCase')!.x);
    expect(pos.get('useCase')!.x).toBeLessThan(pos.get('presenter')!.x);
    expect(pos.get('session')!.x).toBeLessThan(pos.get('presenter')!.x);
    assertNoOverlaps(pos);
  });

  it('never overlaps nodes, even in a single dense column', () => {
    // 20 independent nodes all rank 0
    const nodes = Array.from({ length: 20 }, (_, i) => box(`n${String(i).padStart(2, '0')}`));
    const pos = layeredLayout(nodes, []);
    expect(pos.size).toBe(20);
    assertNoOverlaps(pos);
  });

  it('is deterministic regardless of input order', () => {
    const nodes = ['a', 'b', 'c', 'd', 'e'].map(box);
    const edges = [edge('c', 'a'), edge('c', 'b'), edge('d', 'c'), edge('e', 'c')];
    const forward = layeredLayout(nodes, edges);
    const shuffled = layeredLayout([...nodes].reverse(), [...edges].reverse());
    for (const [id, p] of forward) {
      expect(shuffled.get(id)).toEqual(p);
    }
  });

  it('tolerates cycles (Provider/Lazy edges) and still terminates with all nodes placed', () => {
    const nodes = ['a', 'b', 'c'].map(box);
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a')]; // full cycle
    const pos = layeredLayout(nodes, edges);
    expect(pos.size).toBe(3);
    assertNoOverlaps(pos);
  });

  it('stacks disconnected components vertically without mixing', () => {
    const nodes = ['a1', 'a2', 'b1', 'b2'].map(box);
    const edges = [edge('a2', 'a1'), edge('b2', 'b1')];
    const pos = layeredLayout(nodes, edges);
    assertNoOverlaps(pos);
    const aBottom = Math.max(pos.get('a1')!.y, pos.get('a2')!.y) + CARD.h;
    const bTop = Math.min(pos.get('b1')!.y, pos.get('b2')!.y);
    const aTop = Math.min(pos.get('a1')!.y, pos.get('a2')!.y);
    const bBottom = Math.max(pos.get('b1')!.y, pos.get('b2')!.y) + CARD.h;
    const separated = aBottom < bTop || bBottom < aTop;
    expect(separated, 'components must occupy disjoint vertical bands').toBe(true);
  });

  it('lays out the real demo graph as a readable tree', () => {
    const fixture = fileURLToPath(new URL('../../mock/fixtures/graph.json', import.meta.url));
    const snapshot = JSON.parse(readFileSync(fixture, 'utf-8')) as GraphSnapshot;
    const nodes = snapshot.nodes.map((n) => box(n.id));
    const edges = snapshot.edges.map((e) => edge(e.from, e.to));
    const pos = layeredLayout(nodes, edges);

    expect(pos.size).toBe(snapshot.nodes.length);
    assertNoOverlaps(pos);
    // every dependency edge points right-to-left (dep left of consumer)
    for (const e of snapshot.edges) {
      expect(
        pos.get(e.to)!.x,
        `${e.to} (dependency) must sit left of ${e.from} (consumer)`,
      ).toBeLessThan(pos.get(e.from)!.x);
    }
    // more than one column and more than one row → an actual 2D tree
    const xs = new Set([...pos.values()].map((p) => Math.round(p.x)));
    const ys = new Set([...pos.values()].map((p) => Math.round(p.y)));
    expect(xs.size).toBeGreaterThan(2);
    expect(ys.size).toBeGreaterThan(2);
  });
});
