/**
 * Deterministic layered ("tree") layout — the PRIMARY layout of the board.
 *
 * Sugiyama-style, dependency-free and synchronous, so nodes are always placed
 * even if the ELK worker never answers (elkjs runs as an async *refinement*,
 * see LayoutController usage in main.ts):
 *
 *   1. rank: dependencies left, consumers right — rank = 1 + max(rank(deps)),
 *      cycle edges ignored during the DFS (Provider/Lazy cycles stay drawable)
 *   2. order within a column: barycenter sweeps (L→R, R→L) to reduce crossings
 *   3. coordinates: columns at accumulated widths + gutter; nodes stacked with
 *      real card heights (overlap-impossible), then pulled toward the mean of
 *      their neighbors with collision resolution
 *   4. determinism: every sort is stable with id tiebreaks — same graph, same
 *      picture; disconnected components are laid out separately and stacked
 */

export interface LayoutBox {
  id: string;
  w: number;
  h: number;
}

export interface EdgeRef {
  /** Consumer node id (graph.json `from`). */
  from: string;
  /** Dependency node id (graph.json `to`). */
  to: string;
}

export interface Position {
  x: number;
  y: number;
}

const GAP_X = 130; // gutter between columns
const GAP_Y = 26; // vertical gap between cards in a column
const COMPONENT_GAP = 90; // vertical gap between disconnected subgraphs
const SWEEPS = 4;
const ALIGN_PASSES = 2;

export function layeredLayout(nodes: LayoutBox[], edges: EdgeRef[]): Map<string, Position> {
  const result = new Map<string, Position>();
  if (nodes.length === 0) return result;

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const cleanEdges = edges.filter(
    (e) => e.from !== e.to && byId.has(e.from) && byId.has(e.to),
  );

  // adjacency: deps(consumer) → its dependencies; consumers(dep) → who injects it
  const deps = new Map<string, string[]>();
  const consumers = new Map<string, string[]>();
  for (const n of nodes) {
    deps.set(n.id, []);
    consumers.set(n.id, []);
  }
  for (const e of cleanEdges) {
    deps.get(e.from)!.push(e.to);
    consumers.get(e.to)!.push(e.from);
  }

  const rank = assignRanks(nodes, deps);
  tightenRanks(nodes, consumers, rank);
  const maxRank = Math.max(...rank.values());

  // global column x positions (shared across components so columns line up)
  const colWidth: number[] = Array.from({ length: maxRank + 1 }, () => 0);
  for (const n of nodes) {
    const r = rank.get(n.id)!;
    colWidth[r] = Math.max(colWidth[r]!, n.w);
  }
  const colX: number[] = [];
  let x = 0;
  for (let r = 0; r <= maxRank; r++) {
    colX.push(x);
    x += colWidth[r]! + GAP_X;
  }

  // disconnected subgraphs: layout independently, stack vertically
  let componentTop = 0;
  for (const component of components(nodes, deps, consumers)) {
    const height = layoutComponent(component, rank, deps, consumers, colX, colWidth, componentTop, result);
    componentTop += height + COMPONENT_GAP;
  }
  return result;
}

/** Longest-path layering; edges on the DFS stack (cycles) contribute rank 0. */
function assignRanks(nodes: LayoutBox[], deps: Map<string, string[]>): Map<string, number> {
  const rank = new Map<string, number>();
  const visiting = new Set<string>();

  const visit = (id: string): number => {
    const known = rank.get(id);
    if (known !== undefined) return known;
    if (visiting.has(id)) return 0; // cycle back-edge: ignore for ranking
    visiting.add(id);
    let r = 0;
    for (const dep of deps.get(id) ?? []) r = Math.max(r, visit(dep) + 1);
    visiting.delete(id);
    rank.set(id, r);
    return r;
  };

  for (const n of [...nodes].sort((a, b) => a.id.localeCompare(b.id))) visit(n.id);
  return rank;
}

/**
 * Rank tightening: pull every provider right, next to its nearest consumer
 * (`rank = min(rank(consumers)) - 1`), so edges stay short — a leaf provided in
 * column 0 but only consumed in column 4 moves to column 3. Longest-path ranks
 * guarantee `min(consumers) - 1 >= rank`, so ranks only grow; processing in
 * decreasing rank order with two passes reaches the fixpoint.
 */
function tightenRanks(
  nodes: LayoutBox[],
  consumers: Map<string, string[]>,
  rank: Map<string, number>,
): void {
  for (let pass = 0; pass < 2; pass++) {
    const byRankDesc = [...nodes].sort(
      (a, b) => rank.get(b.id)! - rank.get(a.id)! || a.id.localeCompare(b.id),
    );
    for (const n of byRankDesc) {
      const consumerRanks = (consumers.get(n.id) ?? []).map((id) => rank.get(id)!);
      if (consumerRanks.length === 0) continue; // entry points keep their column
      rank.set(n.id, Math.max(rank.get(n.id)!, Math.min(...consumerRanks) - 1));
    }
  }
}

/** Undirected connected components, deterministic order (smallest member id). */
function components(
  nodes: LayoutBox[],
  deps: Map<string, string[]>,
  consumers: Map<string, string[]>,
): LayoutBox[][] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const seen = new Set<string>();
  const out: LayoutBox[][] = [];
  for (const start of [...nodes].sort((a, b) => a.id.localeCompare(b.id))) {
    if (seen.has(start.id)) continue;
    const queue = [start.id];
    const members: LayoutBox[] = [];
    seen.add(start.id);
    while (queue.length > 0) {
      const id = queue.shift()!;
      members.push(byId.get(id)!);
      for (const next of [...(deps.get(id) ?? []), ...(consumers.get(id) ?? [])]) {
        if (!seen.has(next)) {
          seen.add(next);
          queue.push(next);
        }
      }
    }
    out.push(members);
  }
  // biggest component first — the "main" graph reads top-left
  return out.sort((a, b) => b.length - a.length || a[0]!.id.localeCompare(b[0]!.id));
}

/** Lays out one component below `top`; returns its height. */
function layoutComponent(
  members: LayoutBox[],
  rank: Map<string, number>,
  deps: Map<string, string[]>,
  consumers: Map<string, string[]>,
  colX: number[],
  colWidth: number[],
  top: number,
  result: Map<string, Position>,
): number {
  // columns of this component
  const layers = new Map<number, LayoutBox[]>();
  for (const n of members) {
    const r = rank.get(n.id)!;
    (layers.get(r) ?? layers.set(r, []).get(r)!).push(n);
  }
  const ranks = [...layers.keys()].sort((a, b) => a - b);
  for (const r of ranks) layers.get(r)!.sort((a, b) => a.id.localeCompare(b.id));

  const orderIndex = new Map<string, number>();
  const reindex = (): void => {
    for (const r of ranks) layers.get(r)!.forEach((n, i) => orderIndex.set(n.id, i));
  };
  reindex();

  // 2. crossing reduction: barycenter sweeps, alternating direction
  for (let sweep = 0; sweep < SWEEPS; sweep++) {
    const leftToRight = sweep % 2 === 0;
    const order = leftToRight ? ranks : [...ranks].reverse();
    for (const r of order) {
      const layer = layers.get(r)!;
      const neighborsOf = (id: string): string[] =>
        leftToRight ? deps.get(id) ?? [] : consumers.get(id) ?? [];
      const bary = new Map<string, number>();
      for (const n of layer) {
        const near = neighborsOf(n.id)
          .map((id) => orderIndex.get(id))
          .filter((i): i is number => i !== undefined);
        bary.set(
          n.id,
          near.length > 0 ? near.reduce((s, i) => s + i, 0) / near.length : orderIndex.get(n.id)!,
        );
      }
      layer.sort((a, b) => bary.get(a.id)! - bary.get(b.id)! || a.id.localeCompare(b.id));
      reindex();
    }
  }

  // 3a. initial y: stack every column from the component top
  const y = new Map<string, number>();
  for (const r of ranks) {
    let cursor = top;
    for (const n of layers.get(r)!) {
      y.set(n.id, cursor);
      cursor += n.h + GAP_Y;
    }
  }

  // 3b. pull nodes toward the mean of ALL their neighbors, keep columns
  //     collision-free by re-stacking in desired order
  for (let pass = 0; pass < ALIGN_PASSES; pass++) {
    for (const r of ranks) {
      const layer = layers.get(r)!;
      const desired = new Map<string, number>();
      for (const n of layer) {
        const near = [...(deps.get(n.id) ?? []), ...(consumers.get(n.id) ?? [])]
          .filter((id) => y.has(id))
          .map((id) => y.get(id)!);
        desired.set(
          n.id,
          near.length > 0 ? near.reduce((s, v) => s + v, 0) / near.length : y.get(n.id)!,
        );
      }
      const inOrder = [...layer].sort(
        (a, b) => desired.get(a.id)! - desired.get(b.id)! || a.id.localeCompare(b.id),
      );
      let cursor = top;
      for (const n of inOrder) {
        const target = Math.max(desired.get(n.id)!, cursor);
        y.set(n.id, target);
        cursor = target + n.h + GAP_Y;
      }
    }
  }

  // 3c. write positions, centered horizontally inside their column
  let bottom = top;
  for (const n of members) {
    const r = rank.get(n.id)!;
    result.set(n.id, {
      x: colX[r]! + (colWidth[r]! - n.w) / 2,
      y: y.get(n.id)!,
    });
    bottom = Math.max(bottom, y.get(n.id)! + n.h);
  }
  return bottom - top;
}
