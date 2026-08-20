/**
 * Edges: bezier from provider (dependency) to consumer with the
 * arrowhead at the consumer; Provider/Lazy (deferred) edges dashed; `field`
 * sites get a lightning glyph at the arrowhead. Derived edges have
 * their own voice: `binds` violet dotted with a hollow arrowhead, `viewModel`
 * teal dash-dot. Draw-in animation uses a stroke-dash sweep. LOD: thin
 * straight lines when zoomed out.
 */

import type { VEdge, VNode } from './Scene';
import { LOD_TEXT_THRESHOLD } from './NodeRenderer';
import { theme, withAlpha } from './theme';

export interface EdgeDrawOpts {
  zoom: number;
  highlighted: boolean;
  dim: number;
}

interface BezierGeom {
  x0: number;
  y0: number;
  c1x: number;
  c1y: number;
  c2x: number;
  c2y: number;
  x1: number;
  y1: number;
}

/**
 * Edge geometry: starts at the dependency (provider) card's right edge and
 * ends at the consumer card's left edge — matching the left→right layout.
 * Falls back gracefully when the layout put them the other way around.
 */
function geometry(from: VNode, to: VNode): BezierGeom {
  // `from` = consumer (above), `to` = dependency (below). Flow rises: the
  // dependency's top edge feeds the consumer's bottom edge.
  const start = { x: to.x + to.w / 2, y: to.y };
  const end = { x: from.x + from.w / 2, y: from.y + from.h };
  if (end.y > start.y - to.h / 2) {
    // consumer sits below its dependency (cycle/pin) — route the other way round
    const s = { x: to.x + to.w / 2, y: to.y + to.h };
    const e = { x: from.x + from.w / 2, y: from.y };
    const dy = Math.max(40, Math.abs(e.y - s.y) / 2);
    return { x0: s.x, y0: s.y, c1x: s.x, c1y: s.y + dy, c2x: e.x, c2y: e.y - dy, x1: e.x, y1: e.y };
  }
  const dy = Math.max(40, Math.min(160, (start.y - end.y) / 2 + Math.abs(end.x - start.x) / 4));
  return {
    x0: start.x,
    y0: start.y,
    c1x: start.x,
    c1y: start.y - dy,
    c2x: end.x,
    c2y: end.y + dy,
    x1: end.x,
    y1: end.y,
  };
}

function approxLength(g: BezierGeom): number {
  const dx = g.x1 - g.x0;
  const dy = g.y1 - g.y0;
  return Math.hypot(dx, dy) * 1.15 + 20;
}

/** Cubic bezier point + tangent at t. */
function pointAt(g: BezierGeom, t: number): { x: number; y: number; tx: number; ty: number } {
  const mt = 1 - t;
  const x = mt ** 3 * g.x0 + 3 * mt ** 2 * t * g.c1x + 3 * mt * t ** 2 * g.c2x + t ** 3 * g.x1;
  const y = mt ** 3 * g.y0 + 3 * mt ** 2 * t * g.c1y + 3 * mt * t ** 2 * g.c2y + t ** 3 * g.y1;
  const tx = 3 * mt ** 2 * (g.c1x - g.x0) + 6 * mt * t * (g.c2x - g.c1x) + 3 * t ** 2 * (g.x1 - g.c2x);
  const ty = 3 * mt ** 2 * (g.c1y - g.y0) + 6 * mt * t * (g.c2y - g.c1y) + 3 * t ** 2 * (g.y1 - g.c2y);
  return { x, y, tx, ty };
}

function drawArrowhead(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  angle: number,
  size: number,
  color: string,
  hollow = false,
): void {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(angle);
  ctx.beginPath();
  if (hollow) {
    // UML-style open triangle — "realizes this interface"
    ctx.moveTo(0, 0);
    ctx.lineTo(-size, -size * 0.6);
    ctx.lineTo(-size, size * 0.6);
    ctx.closePath();
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;
    ctx.stroke();
  } else {
    ctx.moveTo(0, 0);
    ctx.lineTo(-size, -size * 0.55);
    ctx.lineTo(-size * 0.7, 0);
    ctx.lineTo(-size, size * 0.55);
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.fill();
  }
  ctx.restore();
}

/** ⚡ glyph for field injection sites. */
function drawFieldGlyph(ctx: CanvasRenderingContext2D, x: number, y: number, color: string): void {
  ctx.save();
  ctx.translate(x, y);
  ctx.beginPath();
  ctx.moveTo(1.5, -6);
  ctx.lineTo(-3, 0.5);
  ctx.lineTo(0, 0.5);
  ctx.lineTo(-1.5, 6);
  ctx.lineTo(3, -0.5);
  ctx.lineTo(0, -0.5);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
  ctx.restore();
}

export class EdgeRenderer {
  draw(ctx: CanvasRenderingContext2D, ve: VEdge, from: VNode, to: VNode, opts: EdgeDrawOpts): void {
    const alpha = ve.alpha * Math.min(from.alpha, to.alpha) * opts.dim;
    if (alpha <= 0.01) return;

    const g = geometry(from, to);
    const deferred = ve.edge.deferred !== 'none';
    const kind = ve.edge.siteKind;
    const color = opts.highlighted
      ? theme.edgeHighlight
      : kind === 'binds'
        ? theme.edgeBinds
        : kind === 'viewModel'
          ? theme.edgeViewModel
          : theme.edge;

    ctx.save();
    ctx.globalAlpha = alpha;

    if (opts.zoom < LOD_TEXT_THRESHOLD) {
      // LOD: 1 px straight line
      ctx.beginPath();
      ctx.moveTo(g.x0, g.y0);
      ctx.lineTo(g.x1, g.y1);
      ctx.strokeStyle = withAlpha(opts.highlighted ? theme.edgeHighlight : '#7a87a5', 0.35);
      ctx.lineWidth = 1 / opts.zoom;
      ctx.stroke();
      ctx.restore();
      return;
    }

    ctx.beginPath();
    ctx.moveTo(g.x0, g.y0);
    ctx.bezierCurveTo(g.c1x, g.c1y, g.c2x, g.c2y, g.x1, g.y1);
    ctx.strokeStyle = color;
    ctx.lineWidth = opts.highlighted ? 2.25 : 1.5;

    if (ve.drawProgress < 1) {
      // dash-draw animation from provider → consumer
      const len = approxLength(g);
      ctx.setLineDash([len * ve.drawProgress, len]);
    } else if (kind === 'binds') {
      ctx.setLineDash([2, 4]);
    } else if (kind === 'viewModel') {
      ctx.setLineDash([9, 4, 2, 4]);
    } else if (deferred) {
      ctx.setLineDash([7, 5]);
    }
    ctx.stroke();
    ctx.setLineDash([]);

    // arrowhead at consumer (only once the draw-in reaches it)
    if (ve.drawProgress >= 0.98) {
      const tip = pointAt(g, 1);
      const angle = Math.atan2(tip.ty, tip.tx);
      drawArrowhead(ctx, g.x1, g.y1, angle, 9, color, kind === 'binds');
      if (kind === 'field') {
        const back = pointAt(g, 0.9);
        drawFieldGlyph(ctx, back.x - 10, back.y, opts.highlighted ? theme.edgeHighlight : theme.amber);
      }
    }

    ctx.restore();
  }
}
