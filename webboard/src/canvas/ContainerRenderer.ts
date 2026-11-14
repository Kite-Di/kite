/**
 * Module container backdrop: a translucent labeled rounded rect
 * enclosing one lane's nodes, drawn under the edges and cards so it reads as a
 * grouping tint rather than a foreground shape. Geometry comes from
 * `model/containers.ts`; this is only the paint.
 */

import type { Container } from '../model/containers';
import { theme, withAlpha } from './theme';

const RADIUS = 12;
const LABEL_FONT = `600 12px ${theme.font}`;
const COUNT_FONT = `500 12px ${theme.font}`;
const CHIP_H = 22;
/** Below this zoom, labels (like card text) are dropped — only the tint remains. */
const LABEL_LOD = 0.35;

export interface ContainerDrawOpts {
  color: string;
  zoom: number;
  /** Extra multiplier (e.g. recede while a node is selected). */
  dim: number;
  /** The module is selected — it is the one a drag would move. */
  selected?: boolean;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

/**
 * How far the containers recede while a card is focused. Only a little: the
 * focused card's neighbours are exactly where "which module is this in" matters.
 */
export const CONTAINER_FOCUS_DIM = 0.8;

/**
 * Alphas and border width (screen px) for one container. The box has to read as a
 * box at a glance — and in a screenshot scaled down to a docs column — so the
 * border is solid enough to enclose its cards, not a faint outline.
 */
export function containerStyle(selected: boolean, dim: number): { fill: number; stroke: number; lineWidth: number; label: number } {
  return {
    fill: (selected ? 0.14 : 0.08) * dim,
    stroke: (selected ? 1 : 0.7) * dim,
    lineWidth: selected ? 2.5 : 2,
    label: dim,
  };
}

export class ContainerRenderer {
  draw(ctx: CanvasRenderingContext2D, c: Container, opts: ContainerDrawOpts): void {
    const { color, zoom, dim, selected = false } = opts;
    const style = containerStyle(selected, dim);
    ctx.save();

    // translucent fill + accent-tinted border, same hue as the cards inside.
    // Selected: the tint comes forward and the border goes solid, so "this is the
    // module a drag would move" is visible before the drag starts.
    roundRect(ctx, c.x, c.y, c.w, c.h, RADIUS);
    ctx.fillStyle = withAlpha(color, style.fill);
    ctx.fill();
    ctx.strokeStyle = withAlpha(color, style.stroke);
    ctx.lineWidth = style.lineWidth / zoom;
    ctx.stroke();

    // label chip — colour square, module name, class count; dropped at low zoom like card text
    if (zoom >= LABEL_LOD) {
      const count = String(c.size);
      ctx.font = LABEL_FONT;
      const nameW = ctx.measureText(c.lane).width;
      ctx.font = COUNT_FONT;
      const countW = ctx.measureText(count).width;
      const x = c.x + 10;
      const y = c.y + 8;
      const w = 8 + 8 + 6 + nameW + 6 + countW + 8;
      const mid = y + CHIP_H / 2 + 0.5;

      ctx.globalAlpha = style.label;
      roundRect(ctx, x, y, w, CHIP_H, 6);
      ctx.fillStyle = theme.cardBg;
      ctx.fill();
      ctx.strokeStyle = withAlpha(color, selected ? 0.9 : 0.5);
      ctx.lineWidth = 1 / zoom;
      ctx.stroke();
      roundRect(ctx, x + 8, y + 7, 8, 8, 2);
      ctx.fillStyle = color;
      ctx.fill();
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.font = LABEL_FONT;
      ctx.fillStyle = theme.text;
      ctx.fillText(c.lane, x + 22, mid);
      ctx.font = COUNT_FONT;
      ctx.fillStyle = theme.textMuted;
      ctx.fillText(count, x + 22 + nameW + 6, mid);
      ctx.globalAlpha = 1;
    }

    ctx.restore();
  }
}
