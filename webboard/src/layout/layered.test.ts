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

describe('layeredLayout (vertical, dot-style)', () => {
  it('places every dependency strictly below its consumer', () => {
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
    expect(pos.get('presenter')!.y).toBeLessThan(pos.get('useCase')!.y);
    expect(pos.get('useCase')!.y).toBeLessThan(pos.get('repo')!.y);
    expect(pos.get('repo')!.y).toBeLessThan(pos.get('api')!.y);
    expect(pos.get('presenter')!.y).toBeLessThan(pos.get('session')!.y);
    assertNoOverlaps(pos);
  });

  it('aligns a chain into one straight vertical line (one after another)', () => {
    const nodes = ['a', 'b', 'c', 'd'].map(box);
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'd')];
    const pos = layeredLayout(nodes, edges);
    const centers = ['a', 'b', 'c', 'd'].map((id) => pos.get(id)!.x + CARD.w / 2);
    for (const c of centers) {
      expect(Math.abs(c - centers[0]!), 'chain must be vertically aligned').toBeLessThan(1);
    }
    // and strictly one after another, top to bottom
    const ys = ['a', 'b', 'c', 'd'].map((id) => pos.get(id)!.y);
    expect(ys).toEqual([...ys].sort((p, q) => p - q));
  });

  it('never overlaps nodes, even in a single dense row', () => {
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

  it('tolerates cycles (Provider/Lazy edges) and still places all nodes', () => {
    const nodes = ['a', 'b', 'c'].map(box);
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a')];
    const pos = layeredLayout(nodes, edges);
    expect(pos.size).toBe(3);
    assertNoOverlaps(pos);
  });

  it('hangs every dependency directly below its deepest consumer', () => {
    // chain a→b→c→d plus leaf consumed only by a — leaf hangs right below a (row of b)
    const nodes = ['a', 'b', 'c', 'd', 'leaf'].map(box);
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'd'), edge('a', 'leaf')];
    const pos = layeredLayout(nodes, edges);
    expect(pos.get('leaf')!.y).toBe(pos.get('b')!.y);
    // and all roots share the top row
    expect(pos.get('a')!.y).toBe(0);
    assertNoOverlaps(pos);
  });

  it('reduces crossings: parallel chains do not interleave', () => {
    // two independent chains: x1→x2→x3 and y1→y2→y3 — each stays on its side
    const nodes = ['x1', 'x2', 'x3', 'y1', 'y2', 'y3'].map(box);
    const edges = [edge('x1', 'x2'), edge('x2', 'x3'), edge('y1', 'y2'), edge('y2', 'y3')];
    const pos = layeredLayout(nodes, edges);
    const xSide = Math.sign(pos.get('x1')!.x - pos.get('y1')!.x);
    expect(Math.sign(pos.get('x2')!.x - pos.get('y2')!.x)).toBe(xSide);
    expect(Math.sign(pos.get('x3')!.x - pos.get('y3')!.x)).toBe(xSide);
    assertNoOverlaps(pos);
  });

  it('lays out the real demo graph: top-down flow, no overlaps, multiple rows and columns', () => {
    const fixture = fileURLToPath(new URL('../../mock/fixtures/graph.json', import.meta.url));
    const snapshot = JSON.parse(readFileSync(fixture, 'utf-8')) as GraphSnapshot;
    const nodes = snapshot.nodes.map((n) => box(n.id));
    // real edges + synthetic interface→implementation edges, exactly like the app
    const ids = new Set(snapshot.nodes.map((n) => n.id));
    const edges = [
      ...snapshot.edges.map((e) => edge(e.from, e.to)),
      ...snapshot.nodes.flatMap((n) => n.boundTo.filter((b) => ids.has(b)).map((b) => edge(b, n.id))),
    ];
    const pos = layeredLayout(nodes, edges);

    expect(pos.size).toBe(snapshot.nodes.length);
    assertNoOverlaps(pos);
    // every dependency edge flows downward (dependency below consumer)
    for (const e of snapshot.edges) {
      expect(
        pos.get(e.to)!.y,
        `${e.to} (dependency) must sit below ${e.from} (consumer)`,
      ).toBeGreaterThan(pos.get(e.from)!.y);
    }
    const ys = new Set([...pos.values()].map((p) => Math.round(p.y)));
    expect(ys.size).toBeGreaterThan(3); // a real vertical cascade
  });
});
