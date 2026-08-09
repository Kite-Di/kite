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
// Horizontal gap between module bands. Must exceed 2× the container padding
// so neighbouring module boxes keep clear air between them.
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
 * Module-banded layout: rows are the global dependency depth (so a
 * rank-2 node in one module lines up with a rank-2 node in another and every edge
 * still reads top-down), while each ownership lane is confined to its own disjoint
 * horizontal band — the module container boxes drawn around them therefore can
 * never overlap. Within a band the full dot coordinate pass runs over the module's
 * *own* edges (`orderAndPlaceX`), so each module's internal graph is as tidy —
 * crossings cut, chains straightened into vertical lines — as a single-module
 * flow. Deterministic: lanes ordered by name, nodes by id.
 */
function bandedLayout(nodes: LayoutBox[], edges: EdgeRef[], laneOf: Map<string, string>): Map<string, Position> {
  const cleanEdges = cleanEdgesOf(nodes, edges);
  // Rows = global dependency depth, shared across all bands.
  const rowOf = assignRanks(nodes, consumersOf(nodes, cleanEdges));
  const maxRank = Math.max(...rowOf.values());
  const rowY = computeRowY(nodes, rowOf, maxRank);

  // Group nodes by lane; order lanes deterministically (by name).
  const byLane = new Map<string, LayoutBox[]>();
  for (const n of nodes) {
    const lane = laneOf.get(n.id) ?? '';
    (byLane.get(lane) ?? byLane.set(lane, []).get(lane)!).push(n);
  }
  const laneOrder = [...byLane.keys()].sort((a, b) => a.localeCompare(b));

  const positions = new Map<string, Position>();
  let cursor = 0; // left edge of the current band
  for (const lane of laneOrder) {
    const laneNodes = byLane.get(lane)!;
    const laneIds = new Set(laneNodes.map((n) => n.id));
    // Only edges internal to the module drive its ordering — cross-module edges
    // become long inter-band curves and shouldn't tangle a module's own layout.
    const laneEdges = cleanEdges.filter((e) => laneIds.has(e.from) && laneIds.has(e.to));
    // Tidy the module internally (shares the whole-graph coordinate pass), then
    // shift the whole band so its leftmost card sits at `cursor`; bands stay
    // disjoint, left → right, BAND_GAP apart.
    const localX = orderAndPlaceX(laneNodes, laneEdges, rowOf, maxRank);
    let minX = Infinity;
    let maxX = -Infinity;
    for (const n of laneNodes) {
      const lx = localX.get(n.id)!;
      minX = Math.min(minX, lx);
      maxX = Math.max(maxX, lx + n.w);
    }
    const shift = cursor - minX;
    for (const n of laneNodes) {
      positions.set(n.id, { x: localX.get(n.id)! + shift, y: rowY[rowOf.get(n.id)!]! });
    }
    cursor += maxX - minX + BAND_GAP;
  }
  return positions;
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
