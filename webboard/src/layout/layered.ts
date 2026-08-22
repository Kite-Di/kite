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
 *
 * Steps 2–3 (the crossing reduction + coordinate assignment) are factored into
 * `orderAndPlaceX`, so the whole-graph flow and every individual module band run
 * the *same* quality pass — a module's internal graph reads as cleanly as the
 * single-module case.
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
// Vertical gap between stacked module bands. Must exceed the container padding
// below plus the padding *and* header above (24 + 24 + 22 = 70) so
// neighbouring module boxes keep clear air between them.
const BAND_GAP = 140;

export function layeredLayout(
  nodes: LayoutBox[],
  edges: EdgeRef[],
  /**
   * Optional ownership labels. With ≥ 2 distinct lanes the layout switches to
   * disjoint horizontal **module bands** (`bandedLayout`), so each module's
   * container box is a separate column that can never overlap another's
   *. Without lanes (or a single lane) the classic dot-style flow
   * below is used.
   */
  laneOf?: Map<string, string>,
): Map<string, Position> {
  const positions = new Map<string, Position>();
  if (nodes.length === 0) return positions;

  if (laneOf && distinctLanes(nodes, laneOf) >= 2) return bandedLayout(nodes, edges, laneOf);

  const cleanEdges = cleanEdgesOf(nodes, edges);
  const rowOf = assignRanks(nodes, consumersOf(nodes, cleanEdges));
  const maxRank = Math.max(...rowOf.values());
  const rowY = computeRowY(nodes, rowOf, maxRank);
  const x = orderAndPlaceX(nodes, cleanEdges, rowOf, maxRank);

  for (const n of nodes) {
    positions.set(n.id, { x: x.get(n.id)!, y: rowY[rowOf.get(n.id)!]! });
  }
  return positions;
}

/** Distinct non-empty ownership lanes present among [nodes]. */
function distinctLanes(nodes: LayoutBox[], laneOf: Map<string, string>): number {
  const seen = new Set<string>();
  for (const n of nodes) {
    const lane = laneOf.get(n.id);
    if (lane) seen.add(lane);
  }
  return seen.size;
}

/**
 * Module-banded layout: each ownership lane is confined to its own
 * disjoint band, and the bands are **stacked top → down** in dependency order,
 * BAND_GAP apart, sharing one x centre line — so the module container boxes drawn
 * around them can never overlap, and a cross-module wire points *down* like every
 * other edge (`:app` over `:feature` over `:core` over `platform`, see
 * `orderLanes`).
 *
 * Crucially each module is laid out **in its own local coordinate system**, not
 * against the whole graph's depth: the same four steps re-run over the module's
 * own nodes and edges, so its box is a compact, self-contained top-to-bottom tree
 * instead of a few cards smeared down the graph's entire depth with empty rows
 * between them. Rows therefore obey the one rule the whole board obeys —
 * `assignRanks`, consumer above dependency — so a chain reads downward inside a
 * band exactly as it does outside one. The dot coordinate pass (`orderAndPlaceX`)
 * then cuts crossings and straightens chains on those same internal edges — and,
 * because it packs every row centred on 0, the bands come out centred on each
 * other for free. Deterministic: lanes topologically ordered with name tiebreaks,
 * nodes by id.
 */
function bandedLayout(nodes: LayoutBox[], edges: EdgeRef[], laneOf: Map<string, string>): Map<string, Position> {
  const cleanEdges = cleanEdgesOf(nodes, edges);

  // Group nodes by lane; order lanes deterministically (by name).
  const byLane = new Map<string, LayoutBox[]>();
  for (const n of nodes) {
    const lane = laneOf.get(n.id) ?? '';
    (byLane.get(lane) ?? byLane.set(lane, []).get(lane)!).push(n);
  }
  const laneOrder = orderLanes([...byLane.keys()], cleanEdges, laneOf);

  const positions = new Map<string, Position>();
  let cursor = 0; // top edge of the current band
  for (const lane of laneOrder) {
    const laneNodes = byLane.get(lane)!;
    const laneIds = new Set(laneNodes.map((n) => n.id));
    // Only edges internal to the module drive its ordering — cross-module edges
    // become long inter-band curves and shouldn't tangle a module's own layout.
    const laneEdges = cleanEdges.filter((e) => laneIds.has(e.from) && laneIds.has(e.to));
    // Module-local rows by the *same* top-down rule as the whole-graph flow:
    // a consumer always sits above what it consumes. Screen → presenter → use case
    // → repository reads downward inside the band exactly as it does outside it.
    // A node no module sibling consumes (the module's exposed API) has nothing to
    // sit under, so it tops the band on its own.
    const rowOf = assignRanks(laneNodes, consumersOf(laneNodes, laneEdges));
    const maxRank = Math.max(...rowOf.values());
    const rowY = computeRowY(laneNodes, rowOf, maxRank);
    const localX = orderAndPlaceX(laneNodes, laneEdges, rowOf, maxRank);
    // Shift the whole band down so its topmost card sits at `cursor`; bands stay
    // disjoint, top → down, BAND_GAP apart. x stays local — every band is already
    // centred on 0 by `orderAndPlaceX`, so the stack lines up on one centre line.
    let minY = Infinity;
    let maxY = -Infinity;
    for (const n of laneNodes) {
      const ly = rowY[rowOf.get(n.id)!]!;
      minY = Math.min(minY, ly);
      maxY = Math.max(maxY, ly + n.h);
    }
    const shift = cursor - minY;
    for (const n of laneNodes) {
      positions.set(n.id, { x: localX.get(n.id)!, y: rowY[rowOf.get(n.id)!]! + shift });
    }
    cursor += maxY - minY + BAND_GAP;
  }
  return positions;
}

/**
 * Band order for the vertical stack: **consumers above their dependencies**.
 *
 * Kahn's topological sort over the lane-level graph — every cross-module edge
 * (consumer → dependency) becomes an edge between their lanes, so the lanes
 * nothing else depends on (`:app`) come out first and the ones everything leans on
 * (`:core`, `platform`) sink to the bottom. Ties and lanes left over in a cycle
 * break alphabetically, keeping the whole thing deterministic.
 *
 * Alphabetical order was fine while bands tiled left → right — direction carried
 * no meaning there. Stacked top → down it does: the order *is* the claim that a
 * cross-module wire points downward, so it has to follow the dependencies.
 */
function orderLanes(lanes: string[], edges: EdgeRef[], laneOf: Map<string, string>): string[] {
  const all = [...lanes].sort((a, b) => a.localeCompare(b));
  const dependsOn = new Map<string, Set<string>>(all.map((l) => [l, new Set<string>()]));
  const consumerCount = new Map<string, number>(all.map((l) => [l, 0]));
  for (const e of edges) {
    const from = laneOf.get(e.from) ?? '';
    const to = laneOf.get(e.to) ?? '';
    if (from === to || !dependsOn.has(from) || !dependsOn.has(to)) continue;
    if (dependsOn.get(from)!.has(to)) continue;
    dependsOn.get(from)!.add(to);
    consumerCount.set(to, consumerCount.get(to)! + 1);
  }

  const order: string[] = [];
  const placed = new Set<string>();
  while (order.length < all.length) {
    // Free lane = nothing still unplaced depends on it. None left → a cycle
    // between modules; break it at the alphabetically first lane remaining.
    const next =
      all.find((l) => !placed.has(l) && consumerCount.get(l) === 0) ?? all.find((l) => !placed.has(l))!;
    placed.add(next);
    order.push(next);
    for (const dep of dependsOn.get(next)!) consumerCount.set(dep, consumerCount.get(dep)! - 1);
  }
  return order;
}

/**
 * The coordinate half of dot's algorithm — shared by the whole-graph flow and by
 * each module band. Given nodes, their already-computed rows and the edges to
 * respect, it (a) orders nodes within each row to cut crossings (median sweeps
 * then adjacent transposition) and (b) assigns x by median alignment, so a chain
 * A→B→C converges to a single straight vertical line. Row packing is centred on
 * 0; y is the caller's concern. Running this per module is what makes each
 * module's internal graph read as cleanly as the single-module flow.
 */
function orderAndPlaceX(
  nodes: LayoutBox[],
  edges: EdgeRef[],
  rowOf: Map<string, number>,
  maxRank: number,
): Map<string, number> {
  const byId = new Map(nodes.map((n) => [n.id, n]));

  const deps = new Map<string, string[]>();
  const consumers = new Map<string, string[]>();
  for (const n of nodes) {
    deps.set(n.id, []);
    consumers.set(n.id, []);
  }
  for (const e of edges) {
    deps.get(e.from)!.push(e.to);
    consumers.get(e.to)!.push(e.from);
  }

  const rows: LayoutBox[][] = Array.from({ length: maxRank + 1 }, () => []);
  for (const n of nodes) rows[rowOf.get(n.id)!]!.push(n);
  for (const row of rows) row.sort((a, b) => a.id.localeCompare(b.id));

  const adjacency = new Map<string, string[]>();
  for (const n of nodes) adjacency.set(n.id, [...deps.get(n.id)!, ...consumers.get(n.id)!]);
  const index = new Map<string, number>();
  const reindex = (row: LayoutBox[]): void => row.forEach((n, i) => index.set(n.id, i));
  for (const row of rows) reindex(row);

  const neighborIndices = (id: string, targetRow: number): number[] =>
    adjacency
      .get(id)!
      .filter((other) => rowOf.get(other) === targetRow)
      .map((other) => index.get(other)!);

  // 1 ─ median ordering sweeps (top→bottom, then bottom→up)
  for (let sweep = 0; sweep < MEDIAN_SWEEPS; sweep++) {
    const down = sweep % 2 === 0;
    const order = down ? [...rows.keys()] : [...rows.keys()].reverse();
    for (const r of order) {
      const fixedRow = down ? r - 1 : r + 1;
      if (fixedRow < 0 || fixedRow >= rows.length) continue;
      const row = rows[r]!;
      const medians = new Map<string, number>();
      for (const n of row) medians.set(n.id, median(neighborIndices(n.id, fixedRow)) ?? index.get(n.id)!);
      row.sort((a, b) => medians.get(a.id)! - medians.get(b.id)! || a.id.localeCompare(b.id));
      reindex(row);
    }
  }

  // 2 ─ transpose: swap adjacent pairs while it reduces crossings
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

  // 3 ─ initial x: pack each row, centered around 0
  const x = new Map<string, number>();
  for (const row of rows) {
    const total = row.reduce((s, n) => s + n.w, 0) + NODE_GAP * Math.max(0, row.length - 1);
    let cursor = -total / 2;
    for (const n of row) {
      x.set(n.id, cursor);
      cursor += n.w + NODE_GAP;
    }
  }

  // 4 ─ median alignment sweeps with feasible-average separation
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

  return x;
}

/** Row y positions: each rank is as tall as its tallest card, ROW_GAP between. */
function computeRowY(nodes: LayoutBox[], rowOf: Map<string, number>, maxRank: number): number[] {
  const rowHeight = Array.from({ length: maxRank + 1 }, () => 0);
  for (const n of nodes) {
    const r = rowOf.get(n.id)!;
    rowHeight[r] = Math.max(rowHeight[r]!, n.h);
  }
  const rowY: number[] = [];
  let y = 0;
  for (let r = 0; r <= maxRank; r++) {
    rowY.push(y);
    y += rowHeight[r]! + ROW_GAP;
  }
  return rowY;
}

/** Drops self-loops and dangling endpoints so ranking/ordering can trust every edge. */
function cleanEdgesOf(nodes: LayoutBox[], edges: EdgeRef[]): EdgeRef[] {
  const ids = new Set(nodes.map((n) => n.id));
  return edges.filter((e) => e.from !== e.to && ids.has(e.from) && ids.has(e.to));
}

/** Consumer adjacency (dependency → its consumers), the relation top-down ranking walks. */
function consumersOf(nodes: LayoutBox[], cleanEdges: EdgeRef[]): Map<string, string[]> {
  const consumers = new Map<string, string[]>();
  for (const n of nodes) consumers.set(n.id, []);
  for (const e of cleanEdges) consumers.get(e.to)!.push(e.from);
  return consumers;
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
