/**
 * Edges: bezier from provider (dependency) to consumer with the
 * arrowhead at the consumer; Provider/Lazy (deferred) edges dashed; `field`
 * sites get a lightning glyph at the arrowhead. Draw-in animation uses a
 * stroke-dash sweep. LOD: thin straight lines when zoomed out.
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
  // `from` = consumer, `to` = dependency. Visual flow: dependency → consumer.
  const start = { x: to.x + to.w, y: to.y + to.h / 2 };
  const end = { x: from.x, y: from.y + from.h / 2 };
  if (end.x < start.x - to.w / 2) {
    // consumer is left of provider — route from provider's left to consumer's right
    const s = { x: to.x, y: to.y + to.h / 2 };
    const e = { x: from.x + from.w, y: from.y + from.h / 2 };
    const dx = Math.max(40, Math.abs(e.x - s.x) / 2);
    return { x0: s.x, y0: s.y, c1x: s.x - dx, c1y: s.y, c2x: e.x + dx, c2y: e.y, x1: e.x, y1: e.y };
  }
  const dx = Math.max(40, Math.min(160, (end.x - start.x) / 2 + Math.abs(end.y - start.y) / 4));
  return {
    x0: start.x,
    y0: start.y,
    c1x: start.x + dx,
    c1y: start.y,
    c2x: end.x - dx,
    c2y: end.y,
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

function drawArrowhead(ctx: CanvasRenderingContext2D, x: number, y: number, angle: number, size: number, color: string): void {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(angle);
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.lineTo(-size, -size * 0.55);
  ctx.lineTo(-size * 0.7, 0);
  ctx.lineTo(-size, size * 0.55);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
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
    const color = opts.highlighted ? theme.edgeHighlight : theme.edge;

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
    } else if (deferred) {
      ctx.setLineDash([7, 5]);
    }
    ctx.stroke();
    ctx.setLineDash([]);

    // arrowhead at consumer (only once the draw-in reaches it)
    if (ve.drawProgress >= 0.98) {
      const tip = pointAt(g, 1);
      const angle = Math.atan2(tip.ty, tip.tx);
      drawArrowhead(ctx, g.x1, g.y1, angle, 9, color);
      if (ve.edge.siteKind === 'field') {
        const back = pointAt(g, 0.9);
        drawFieldGlyph(ctx, back.x, back.y - 8, opts.highlighted ? theme.edgeHighlight : theme.amber);
      }
    }

    ctx.restore();
  }
}
