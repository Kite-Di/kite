/**
 * Module container geometry — pure grouping of laid-out node rects
 * into one padded bounding box per ownership lane, so the canvas can draw a
 * labeled backdrop around each module (or package, single-module).
 *
 * Pure and canvas-free: the caller passes the live node rectangles (already
 * positioned by the layout, possibly mid-tween), we return one box per lane. The
 * box therefore tracks nodes as they move — no separate layout pass, no state.
 */

export interface ContainerItem {
  /** Ownership lane (module / package) — the grouping key and label. */
  lane: string;
  /** World-space node rect. */
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Container {
  lane: string;
  /** Padded rect enclosing every node of the lane, with room on top for the header. */
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface ContainerOptions {
  /** Padding around the tightest bounding box, on every side. */
  pad?: number;
  /** Extra space added above for the header label (folded into the top pad). */
  header?: number;
  /** Lanes never drawn as a container (e.g. the platform lane). */
  exclude?: Iterable<string>;
}

const DEFAULT_PAD = 24;
const DEFAULT_HEADER = 22;

/**
 * One [Container] per distinct lane, sorted by lane for deterministic draw order.
 * Empty/excluded lanes are dropped. Returns `[]` for fewer than two drawable
 * lanes — a single box around the whole graph is noise, not information.
 */
export function computeContainers(items: ContainerItem[], options: ContainerOptions = {}): Container[] {
  const pad = options.pad ?? DEFAULT_PAD;
  const header = options.header ?? DEFAULT_HEADER;
  const excluded = new Set(options.exclude ?? []);

  interface Box {
    minX: number;
    minY: number;
    maxX: number;
    maxY: number;
  }
  const byLane = new Map<string, Box>();

  for (const it of items) {
    if (!it.lane || excluded.has(it.lane)) continue;
    const box = byLane.get(it.lane);
    if (!box) {
      byLane.set(it.lane, { minX: it.x, minY: it.y, maxX: it.x + it.w, maxY: it.y + it.h });
    } else {
      box.minX = Math.min(box.minX, it.x);
      box.minY = Math.min(box.minY, it.y);
      box.maxX = Math.max(box.maxX, it.x + it.w);
      box.maxY = Math.max(box.maxY, it.y + it.h);
    }
  }

  if (byLane.size < 2) return [];

  return [...byLane.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([lane, b]) => ({
      lane,
      x: b.minX - pad,
      y: b.minY - pad - header,
      w: b.maxX - b.minX + pad * 2,
      h: b.maxY - b.minY + pad * 2 + header,
    }));
}
