/**
 * Vertical layered layout — the classical Sugiyama method as refined in
 * Graphviz `dot` (Gansner, Koutsofios, North, Vo: "A Technique for Drawing
 * Directed Graphs"). Deterministic, dependency-free, synchronous.
 *
 *   1. Layering, top-down: roots (entry points — nothing consumes them) form
 *      row 0; every dependency sits exactly one row below its deepest consumer
 *      (`rank = 1 + max(rank(consumers))`, cycle back-edges ignored). Each
 *      edge points from a consumer down to its dependency.
 *   2. Crossing reduction: iterated median heuristic over row orderings
 *      (down/up sweeps) followed by adjacent-pair transposition — dot's exact
 *      recipe, substantially better than plain barycenter.
 *   3. Coordinates: per-row packing, then median-alignment sweeps where each
 *      node moves toward the median center of its neighbors; separation is
 *      enforced by a left-greedy and a right-greedy pass whose average is
 *      taken (both are feasible, so the average keeps minimum gaps). Chains
 *      (A→B→C) converge to a single straight vertical line — dependencies
 *      read "one after another".
 *   4. Determinism: every ordering is stable with id tiebreaks.
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

const ROW_GAP = 110; // vertical space between layers
const NODE_GAP = 40; // horizontal space between cards in a row
const MEDIAN_SWEEPS = 4;
const TRANSPOSE_ROUNDS = 4;
const X_SWEEPS = 8;

export function layeredLayout(
  nodes: LayoutBox[],
  edges: EdgeRef[],
  /** Optional ownership labels — used to seed row order so packages stay together. */
  laneOf?: Map<string, string>,
): Map<string, Position> {
  const positions = new Map<string, Position>();
  if (nodes.length === 0) return positions;

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const cleanEdges = edges.filter((e) => e.from !== e.to && byId.has(e.from) && byId.has(e.to));

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

  // 1 ─ layering, top-down: row 0 = roots; deps hang below their consumers.
  const rowOf = assignRanks(nodes, consumers);
  const maxRank = Math.max(...rowOf.values());

  const rows: LayoutBox[][] = Array.from({ length: maxRank + 1 }, () => []);
  for (const n of nodes) rows[rowOf.get(n.id)!]!.push(n);
  for (const row of rows) {
    row.sort((a, b) => {
      const laneA = laneOf?.get(a.id) ?? '';
      const laneB = laneOf?.get(b.id) ?? '';
      return laneA.localeCompare(laneB) || a.id.localeCompare(b.id);
    });
  }

  const adjacency = new Map<string, string[]>();
  for (const n of nodes) {
    adjacency.set(n.id, [...deps.get(n.id)!, ...consumers.get(n.id)!]);
  }
  const index = new Map<string, number>();
  const reindex = (row: LayoutBox[]): void => row.forEach((n, i) => index.set(n.id, i));
  for (const row of rows) reindex(row);

  const neighborIndices = (id: string, targetRow: number): number[] =>
    adjacency
      .get(id)!
      .filter((other) => rowOf.get(other) === targetRow)
      .map((other) => index.get(other)!);

  // 2a ─ median ordering sweeps (top→bottom, then bottom→up)
  for (let sweep = 0; sweep < MEDIAN_SWEEPS; sweep++) {
    const down = sweep % 2 === 0;
    const order = down ? [...rows.keys()] : [...rows.keys()].reverse();
    for (const r of order) {
      const fixedRow = down ? r - 1 : r + 1;
      if (fixedRow < 0 || fixedRow >= rows.length) continue;
      const row = rows[r]!;
      const medians = new Map<string, number>();
      for (const n of row) {
        medians.set(n.id, median(neighborIndices(n.id, fixedRow)) ?? index.get(n.id)!);
      }
      row.sort((a, b) => medians.get(a.id)! - medians.get(b.id)! || a.id.localeCompare(b.id));
      reindex(row);
    }
  }

  // 2b ─ transpose: swap adjacent pairs while it reduces crossings
  for (let round = 0; round < TRANSPOSE_ROUNDS; round++) {
    let improved = false;
    for (let r = 0; r < rows.length; r++) {
      const row = rows[r]!;
      for (let i = 0; i + 1 < row.length; i++) {
        const before = crossingsAround(row[i]!.id, row[i + 1]!.id, r, rowOf, adjacency, index);
        [row[i], row[i + 1]] = [row[i + 1]!, row[i]!];
        reindex(row);
        const after = crossingsAround(row[i]!.id, row[i + 1]!.id, r, rowOf, adjacency, index);
        if (after < before) {
          improved = true;
        } else {
          [row[i], row[i + 1]] = [row[i + 1]!, row[i]!];
          reindex(row);
        }
      }
    }
    if (!improved) break;
  }

  // 3a ─ row y positions
  const rowY: number[] = [];
  let y = 0;
  for (const row of rows) {
    rowY.push(y);
    const height = row.length > 0 ? Math.max(...row.map((n) => n.h)) : 0;
    y += height + ROW_GAP;
  }

  // 3b ─ initial x: pack each row, centered around 0
  const x = new Map<string, number>();
  for (const row of rows) {
    const total = row.reduce((s, n) => s + n.w, 0) + NODE_GAP * Math.max(0, row.length - 1);
    let cursor = -total / 2;
    for (const n of row) {
      x.set(n.id, cursor);
      cursor += n.w + NODE_GAP;
    }
  }

  // 3c ─ median alignment sweeps with feasible-average separation
  const center = (id: string): number => x.get(id)! + byId.get(id)!.w / 2;
  for (let sweep = 0; sweep < X_SWEEPS; sweep++) {
    const down = sweep % 2 === 0;
    const order = down ? [...rows.keys()] : [...rows.keys()].reverse();
    for (const r of order) {
      const row = rows[r]!;
      if (row.length === 0) continue;
      const fixedRow = down ? r - 1 : r + 1;
      const desired = row.map((n) => {
        const near =
          fixedRow >= 0 && fixedRow < rows.length
            ? adjacency.get(n.id)!.filter((o) => rowOf.get(o) === fixedRow).map(center)
            : [];
        // final sweeps balance against both neighbor rows for symmetry
        const all = sweep >= X_SWEEPS - 2 ? adjacency.get(n.id)!.map(center) : near;
        const pool = all.length > 0 ? all : near;
        const target = median(pool);
        return target !== undefined ? target - n.w / 2 : x.get(n.id)!;
      });
      // left-greedy and right-greedy feasible placements; average keeps min gaps
      const left: number[] = [];
      for (let i = 0; i < row.length; i++) {
        const min = i === 0 ? -Infinity : left[i - 1]! + row[i - 1]!.w + NODE_GAP;
        left.push(Math.max(desired[i]!, min));
      }
      const right: number[] = new Array<number>(row.length);
      for (let i = row.length - 1; i >= 0; i--) {
        const max = i === row.length - 1 ? Infinity : right[i + 1]! - row[i]!.w - NODE_GAP;
        right[i] = Math.min(desired[i]!, max);
      }
      for (let i = 0; i < row.length; i++) {
        x.set(row[i]!.id, (left[i]! + right[i]!) / 2);
      }
    }
  }

  for (const n of nodes) {
    positions.set(n.id, { x: x.get(n.id)!, y: rowY[rowOf.get(n.id)!]! });
  }
  return positions;
}

/**
 * Top-down longest-path layering over the consumer relation: roots (nothing
 * consumes them) get row 0; every dependency lands one row below its deepest
 * consumer, which keeps each edge as short as its deepest use allows. Edges on
 * the DFS stack (cycles) contribute row 0.
 */
function assignRanks(nodes: LayoutBox[], consumers: Map<string, string[]>): Map<string, number> {
  const rank = new Map<string, number>();
  const visiting = new Set<string>();

  const visit = (id: string): number => {
    const known = rank.get(id);
    if (known !== undefined) return known;
    if (visiting.has(id)) return 0; // cycle back-edge: ignore for ranking
    visiting.add(id);
    let r = 0;
    for (const consumer of consumers.get(id) ?? []) r = Math.max(r, visit(consumer) + 1);
    visiting.delete(id);
    rank.set(id, r);
    return r;
  };

  for (const n of [...nodes].sort((a, b) => a.id.localeCompare(b.id))) visit(n.id);
  return rank;
}

/** Median of a list of numbers (undefined when empty). */
function median(values: number[]): number | undefined {
  if (values.length === 0) return undefined;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}

/**
 * Crossings contributed by the adjacent (left,right) pair against both
 * neighboring rows — the quantity dot's transpose step minimizes locally: for
 * u left of v, every neighbor pair (a of u, b of v) with a right of b crosses.
 */
function crossingsAround(
  leftId: string,
  rightId: string,
  row: number,
  rowOf: Map<string, number>,
  adjacency: Map<string, string[]>,
  index: Map<string, number>,
): number {
  let crossings = 0;
  for (const adjacentRow of [row - 1, row + 1]) {
    const leftNeighbors = adjacency
      .get(leftId)!
      .filter((o) => rowOf.get(o) === adjacentRow)
      .map((o) => index.get(o)!);
    const rightNeighbors = adjacency
      .get(rightId)!
      .filter((o) => rowOf.get(o) === adjacentRow)
      .map((o) => index.get(o)!);
    for (const a of leftNeighbors) {
      for (const b of rightNeighbors) {
        if (a > b) crossings++;
      }
    }
  }
  return crossings;
}
