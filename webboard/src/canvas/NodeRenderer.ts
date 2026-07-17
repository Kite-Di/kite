/**
 * Node cards:
 *  - rounded rect, displayName + qualifier chip, scope chip (colored),
 *    kind icon, left border colored by Gradle module
 *  - runtime badge: dot + live instance count; hollow when never instantiated
 *  - LOD: below 0.35 zoom → flat rounded rect, text dropped
 */

import type { NodeKind } from '../model/graph';
import type { VNode } from './Scene';
import { moduleColor, scopeColor, theme, withAlpha } from './theme';

export const LOD_TEXT_THRESHOLD = 0.35;
const MIN_W = 180;
const MAX_W = 220;
export const NODE_HEIGHT = 64;
const RADIUS = 10;

const NAME_FONT = `600 13px ${theme.font}`;
const CHIP_FONT = `500 10px ${theme.font}`;
const BADGE_FONT = `600 10px ${theme.font}`;

let measureCtx: CanvasRenderingContext2D | null = null;

function getMeasureCtx(): CanvasRenderingContext2D {
  if (!measureCtx) {
    measureCtx = document.createElement('canvas').getContext('2d')!;
  }
  return measureCtx;
}

/** Card size for a node — deterministic, computed before layout. */
export function measureNode(displayName: string): { w: number; h: number } {
  const ctx = getMeasureCtx();
  ctx.font = NAME_FONT;
  const textW = ctx.measureText(displayName).width;
  const w = Math.max(MIN_W, Math.min(MAX_W, Math.ceil(textW) + 64));
  return { w, h: NODE_HEIGHT };
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function ellipsize(ctx: CanvasRenderingContext2D, text: string, maxW: number): string {
  if (ctx.measureText(text).width <= maxW) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (ctx.measureText(text.slice(0, mid) + '…').width <= maxW) lo = mid;
    else hi = mid - 1;
  }
  return text.slice(0, lo) + '…';
}

/** Small vector kind icons — class / function / interface socket / entry / external. */
function drawKindIcon(ctx: CanvasRenderingContext2D, kind: NodeKind, cx: number, cy: number): void {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.lineWidth = 1.5;
  const c = theme.textMuted;
  ctx.strokeStyle = c;
  ctx.fillStyle = c;
  switch (kind) {
    case 'injectable': {
      // class: rounded square with a dot
      roundRect(ctx, -6, -6, 12, 12, 3);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(0, 0, 2, 0, Math.PI * 2);
      ctx.fill();
      break;
    }
    case 'provides': {
      // function: ƒ
      ctx.font = `600 13px ${theme.font}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('ƒ', 0, 1);
      break;
    }
    case 'boundInterface': {
      // interface socket: open circle with plug notch
      ctx.beginPath();
      ctx.arc(0, 0, 6, Math.PI * 0.35, Math.PI * 1.65);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(3, -4);
      ctx.lineTo(7, 0);
      ctx.lineTo(3, 4);
      ctx.stroke();
      break;
    }
    case 'entryPoint': {
      // entry point: play triangle in a circle
      ctx.beginPath();
      ctx.arc(0, 0, 7, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(-2.5, -3.5);
      ctx.lineTo(3.5, 0);
      ctx.lineTo(-2.5, 3.5);
      ctx.closePath();
      ctx.fill();
      break;
    }
    case 'set': {
      // multibinding: three stacked dots in braces
      ctx.font = `600 13px ${theme.font}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('{', -6, 1);
      ctx.fillText('}', 6, 1);
      for (const dy of [-3.5, 0, 3.5]) {
        ctx.beginPath();
        ctx.arc(0, dy, 1.4, 0, Math.PI * 2);
        ctx.fill();
      }
      break;
    }
    case 'external': {
      // external: dashed square
      ctx.setLineDash([3, 2]);
      roundRect(ctx, -6, -6, 12, 12, 2);
      ctx.stroke();
      ctx.setLineDash([]);
      break;
    }
  }
  ctx.restore();
}

function drawChip(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  color: string,
  filled: boolean,
): number {
  ctx.font = CHIP_FONT;
  const w = Math.ceil(ctx.measureText(text).width) + 12;
  const h = 16;
  roundRect(ctx, x, y, w, h, 8);
  ctx.fillStyle = withAlpha(color, filled ? 0.18 : 0.1);
  ctx.fill();
  ctx.strokeStyle = withAlpha(color, 0.55);
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.fillStyle = color;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, x + 6, y + h / 2 + 0.5);
  return w;
}

export interface NodeDrawOpts {
  zoom: number;
  selected: boolean;
  hovered: boolean;
  liveMode: boolean;
  dim: number;
}

export class NodeRenderer {
  draw(ctx: CanvasRenderingContext2D, vn: VNode, opts: NodeDrawOpts): void {
    const alpha = vn.alpha * opts.dim;
    if (alpha <= 0.01) return;

    const modColor = moduleColor(vn.lane ?? vn.node.providedBy?.gradleModule);
    ctx.save();
    ctx.globalAlpha = alpha;

    // enter/exit scale around the card center; error shake offset
    const cx = vn.x + vn.w / 2;
    const cy = vn.y + vn.h / 2;
    ctx.translate(cx + vn.shake, cy);
    ctx.scale(vn.scale, vn.scale);
    ctx.translate(-cx, -cy);

    const { x, y, w, h } = vn;

    if (opts.zoom < LOD_TEXT_THRESHOLD) {
      // ---- LOD: flat card, no text ----
      roundRect(ctx, x, y, w, h, RADIUS);
      ctx.fillStyle = theme.cardBgLod;
      ctx.fill();
      ctx.fillStyle = withAlpha(modColor, 0.85);
      roundRect(ctx, x, y, 5, h, 2.5);
      ctx.fill();
      const sc = scopeColor(vn.node.scope);
      ctx.fillStyle = withAlpha(sc, 0.5);
      roundRect(ctx, x + 12, y + h - 14, Math.max(10, w * 0.35), 6, 3);
      ctx.fill();
      if (opts.selected) {
        roundRect(ctx, x - 3, y - 3, w + 6, h + 6, RADIUS + 3);
        ctx.strokeStyle = theme.selection;
        ctx.lineWidth = 2 / opts.zoom;
        ctx.stroke();
      }
      ctx.restore();
      return;
    }

    // ---- full card ----
    // soft shadow
    ctx.shadowColor = theme.cardShadow;
    ctx.shadowBlur = 14;
    ctx.shadowOffsetY = 4;
    roundRect(ctx, x, y, w, h, RADIUS);
    ctx.fillStyle = theme.cardBg;
    ctx.fill();
    ctx.shadowColor = 'transparent';
    ctx.shadowBlur = 0;
    ctx.shadowOffsetY = 0;

    // border
    roundRect(ctx, x, y, w, h, RADIUS);
    ctx.strokeStyle = opts.hovered && !opts.selected ? withAlpha(theme.selection, 0.5) : theme.cardBorder;
    ctx.lineWidth = 1;
    ctx.stroke();

    // left border = gradle module color
    ctx.save();
    roundRect(ctx, x, y, w, h, RADIUS);
    ctx.clip();
    ctx.fillStyle = modColor;
    ctx.fillRect(x, y, 3.5, h);
    ctx.restore();

    // runtime glow (instanceCreated)
    if (vn.glow > 0.01) {
      roundRect(ctx, x, y, w, h, RADIUS);
      ctx.strokeStyle = withAlpha(theme.green, 0.75 * vn.glow);
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }

    // update pulse ring
    if (vn.pulse > 0.01) {
      const grow = (1 - vn.pulse) * 12;
      roundRect(ctx, x - 4 - grow, y - 4 - grow, w + 8 + grow * 2, h + 8 + grow * 2, RADIUS + 4 + grow);
      ctx.strokeStyle = withAlpha(theme.accent, 0.7 * vn.pulse);
      ctx.lineWidth = 2;
      ctx.stroke();
    }

    // error halo
    if (vn.errorHalo > 0.01) {
      roundRect(ctx, x - 3, y - 3, w + 6, h + 6, RADIUS + 3);
      ctx.strokeStyle = withAlpha(theme.red, 0.8 * vn.errorHalo);
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }

    // selection outline
    if (opts.selected) {
      roundRect(ctx, x - 3.5, y - 3.5, w + 7, h + 7, RADIUS + 3.5);
      ctx.strokeStyle = theme.selection;
      ctx.lineWidth = 2;
      ctx.stroke();
    }

    // kind icon
    drawKindIcon(ctx, vn.node.kind, x + 22, y + 22);

    // display name
    ctx.font = NAME_FONT;
    ctx.fillStyle = theme.text;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    const nameMaxW = w - 46 - (opts.liveMode ? 22 : 8);
    ctx.fillText(ellipsize(ctx, vn.node.displayName, nameMaxW), x + 38, y + 22);

    // chips row: qualifier + scope
    let chipX = x + 12;
    const chipY = y + h - 26;
    if (vn.node.qualifier) {
      chipX += drawChip(ctx, `@${vn.node.qualifier}`, chipX, chipY, theme.textMuted, false) + 6;
    }
    const scope = vn.node.scope ?? null;
    chipX += drawChip(ctx, scope ?? 'unscoped', chipX, chipY, scopeColor(scope), true) + 6;

    // runtime badge (live mode only)
    if (opts.liveMode && vn.node.kind !== 'external') {
      const bx = x + w - 15;
      const by = y + 15;
      if (vn.instances > 0) {
        ctx.beginPath();
        ctx.arc(bx, by, 8, 0, Math.PI * 2);
        ctx.fillStyle = withAlpha(theme.green, 0.22 + 0.5 * vn.glow);
        ctx.fill();
        ctx.strokeStyle = theme.green;
        ctx.lineWidth = 1.25;
        ctx.stroke();
        ctx.font = BADGE_FONT;
        ctx.fillStyle = theme.green;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(vn.instances > 9 ? '9+' : String(vn.instances), bx, by + 0.5);
      } else {
        // hollow = never instantiated yet
        ctx.beginPath();
        ctx.arc(bx, by, 4.5, 0, Math.PI * 2);
        ctx.strokeStyle = withAlpha(theme.textMuted, 0.7);
        ctx.lineWidth = 1.25;
        ctx.stroke();
      }
    }

    // error badge
    if (vn.error) {
      const bx = x + w - 15;
      const by = y + h - 15;
      ctx.beginPath();
      ctx.arc(bx, by, 7, 0, Math.PI * 2);
      ctx.fillStyle = withAlpha(theme.red, 0.25);
      ctx.fill();
      ctx.strokeStyle = theme.red;
      ctx.lineWidth = 1.25;
      ctx.stroke();
      ctx.font = `700 10px ${theme.font}`;
      ctx.fillStyle = theme.red;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('!', bx, by + 0.5);
    }

    ctx.restore();
  }
}
