/**
 * Node cards, laid out like a workflow node:
 *  - kind icon in a tile tinted with the module colour, then the class name,
 *    then a row of tags (lifetime, qualifier) and the kind in words
 *  - a solid shelf under the card instead of a blur; selection turns it blue
 *  - runtime badge: dot + live instance count; hollow when never instantiated
 *  - LOD: below 0.35 zoom → flat card with the module tile, text dropped
 */

import type { NodeKind } from '../model/graph';
import type { VNode } from './Scene';
import { moduleColor, scopeColor, theme, withAlpha } from './theme';

export const LOD_TEXT_THRESHOLD = 0.35;
const MIN_W = 184;
const MAX_W = 240;
export const NODE_HEIGHT = 58;
const TILE = 30;
/** Name and tags start here, right of the tile. */
const TEXT_X = 9 + TILE + 9;
const RADIUS = 8;
/** Height of the solid shelf under a card — PostHog's elevation. */
const SHELF = 3;

const NAME_FONT = `600 13px ${theme.font}`;
const CHIP_FONT = `500 10.5px ${theme.font}`;
const KIND_FONT = `400 11px ${theme.font}`;
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
  const w = Math.max(MIN_W, Math.min(MAX_W, Math.ceil(textW) + TEXT_X + 14));
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

/** The kind in words, under the class name (and in the details panel). */
export function kindLabel(kind: NodeKind, qualified: boolean): string {
  switch (kind) {
    case 'injectable':
      return 'class';
    case 'boundInterface':
      return 'interface';
    case 'entryPoint':
      return 'ViewModel';
    case 'set':
      return 'multibinding';
    case 'map':
      return 'map';
    case 'supplied':
      return 'supplied';
    case 'provides':
      return 'provides';
    case 'external':
      return qualified ? 'graph argument' : 'from the host';
  }
}

/** Small vector kind icons — class / function / interface socket / entry / external. */
function drawKindIcon(ctx: CanvasRenderingContext2D, kind: NodeKind, cx: number, cy: number, c: string): void {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.lineWidth = 1.5;
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
    case 'supplied': {
      // supplied: a box the project does not own, with an arrow handing it over
      roundRect(ctx, -7, -5, 9, 10, 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(3, 0);
      ctx.lineTo(7, 0);
      ctx.moveTo(5, -2.5);
      ctx.lineTo(7, 0);
      ctx.lineTo(5, 2.5);
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
    case 'map': {
      // map multibinding: key→value dashes in brackets
      ctx.font = `600 13px ${theme.font}`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('[', -6, 1);
      ctx.fillText(']', 6, 1);
      for (const dy of [-3.5, 0, 3.5]) {
        ctx.beginPath();
        ctx.arc(-2.5, dy, 1.2, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(0, dy);
        ctx.lineTo(3.5, dy);
        ctx.stroke();
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

/** A tag; text past [maxW] ends in an ellipsis instead of leaving the card. */
function drawChip(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  color: string,
  filled: boolean,
  maxW = Infinity,
): number {
  ctx.font = CHIP_FONT;
  text = ellipsize(ctx, text, Math.max(12, maxW - 12));
  const w = Math.ceil(ctx.measureText(text).width) + 12;
  const h = 17;
  roundRect(ctx, x, y, w, h, 4);
  ctx.fillStyle = withAlpha(color, filled ? 0.12 : 0.07);
  ctx.fill();
  ctx.strokeStyle = withAlpha(color, 0.3);
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
  /** The node is the subject of a pending decision — the build waits on it. */
  blocked: boolean;
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

    const isExternal = vn.node.kind === 'external';
    const tileX = x + 9;
    const tileY = y + (h - TILE) / 2;

    if (opts.zoom < LOD_TEXT_THRESHOLD) {
      // ---- LOD: flat card and its module tile, no text ----
      roundRect(ctx, x, y, w, h, RADIUS);
      ctx.fillStyle = theme.cardBgLod;
      ctx.fill();
      roundRect(ctx, tileX, tileY, TILE, TILE, 7);
      ctx.fillStyle = withAlpha(modColor, 0.85);
      ctx.fill();
      const sc = scopeColor(vn.node.scope);
      ctx.fillStyle = withAlpha(sc, 0.5);
      roundRect(ctx, x + TEXT_X, y + h - 20, Math.max(10, (w - TEXT_X) * 0.45), 7, 3.5);
      ctx.fill();
      if (opts.selected || opts.blocked) {
        roundRect(ctx, x - 3, y - 3, w + 6, h + 6, RADIUS + 3);
        ctx.strokeStyle = opts.selected ? theme.selection : theme.amber;
        ctx.lineWidth = 2 / opts.zoom;
        ctx.stroke();
      }
      ctx.restore();
      return;
    }

    // ---- full card ----
    // ring outside the card: a pending decision (amber) or the selection (blue)
    const ring = opts.selected ? theme.selection : opts.blocked ? theme.amber : null;
    if (ring) {
      roundRect(ctx, x - 4, y - 4, w + 8, h + SHELF + 8, RADIUS + 4);
      ctx.fillStyle = withAlpha(ring, 0.14);
      ctx.fill();
    }

    // solid shelf under the card, then the face — no blur
    roundRect(ctx, x, y + SHELF, w, h, RADIUS);
    ctx.fillStyle = ring
      ? withAlpha(ring, 0.55)
      : opts.hovered
        ? theme.cardBorderHover
        : theme.cardShelf;
    ctx.fill();
    roundRect(ctx, x, y, w, h, RADIUS);
    ctx.fillStyle = isExternal ? theme.cardBgLod : theme.cardBg;
    ctx.fill();

    // border
    roundRect(ctx, x, y, w, h, RADIUS);
    ctx.strokeStyle = ring ?? (opts.hovered ? theme.cardBorderHover : theme.cardBorder);
    ctx.lineWidth = ring ? 1.5 : 1;
    if (isExternal && !ring) ctx.setLineDash([4, 3]);
    ctx.stroke();
    ctx.setLineDash([]);

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

    // module tile with the kind icon
    roundRect(ctx, tileX, tileY, TILE, TILE, 7);
    ctx.fillStyle = withAlpha(modColor, 0.13);
    ctx.fill();
    ctx.strokeStyle = withAlpha(modColor, 0.32);
    ctx.lineWidth = 1;
    if (isExternal) ctx.setLineDash([3, 2]);
    ctx.stroke();
    ctx.setLineDash([]);
    drawKindIcon(ctx, vn.node.kind, tileX + TILE / 2, tileY + TILE / 2, modColor);

    // display name
    ctx.font = NAME_FONT;
    ctx.fillStyle = theme.text;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    const nameMaxW = w - TEXT_X - (opts.liveMode ? 26 : 10);
    ctx.fillText(ellipsize(ctx, vn.node.displayName, nameMaxW), x + TEXT_X, y + h / 2 - 9);

    // tags row: lifetime (or, for an external, its qualifier), qualifier, then the kind in words
    const right = x + w - 10;
    const chipY = y + h / 2 + 2;
    let chipX = x + TEXT_X;
    const scope = vn.node.scope ?? null;
    if (!isExternal) {
      chipX += drawChip(ctx, scope ?? 'unscoped', chipX, chipY, scopeColor(scope), true, right - chipX) + 5;
    }
    if (vn.node.qualifier && right - chipX > 24) {
      chipX += drawChip(ctx, `@${vn.node.qualifier}`, chipX, chipY, theme.textMuted, false, right - chipX) + 5;
    }
    if (right - chipX > 28) {
      ctx.font = KIND_FONT;
      ctx.fillStyle = theme.textMuted;
      ctx.textBaseline = 'middle';
      ctx.fillText(ellipsize(ctx, kindLabel(vn.node.kind, !!vn.node.qualifier), right - chipX), chipX + 1, chipY + 9);
    }

    // runtime badge (live mode only)
    if (opts.liveMode && vn.node.kind !== 'external') {
      const bx = x + w - 15;
      const by = y + 15;
      // Scoped: solid green = instances alive now. Unscoped: dashed amber = how
      // many times it was built, because nothing holds it.
      const live = vn.instances > 0;
      const built = !live && vn.creations > 0;
      if (live || built) {
        const color = live ? theme.green : theme.amber;
        const count = live ? vn.instances : vn.creations;
        ctx.beginPath();
        ctx.arc(bx, by, 8, 0, Math.PI * 2);
        ctx.fillStyle = withAlpha(color, (live ? 0.22 : 0.12) + 0.5 * vn.glow);
        ctx.fill();
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.25;
        if (built) ctx.setLineDash([2.4, 2.2]);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.font = BADGE_FONT;
        ctx.fillStyle = color;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(count > 9 ? '9+' : String(count), bx, by + 0.5);
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
