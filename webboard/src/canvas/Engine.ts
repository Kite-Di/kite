/**
 * Render engine: single full-viewport canvas, rAF loop gated by a
 * dirty flag (idle board = 0 CPU), devicePixelRatio aware, subtle dot-grid
 * world backdrop. Draws edges below nodes, culled via the Scene spatial hash.
 */

import { computeContainers, type Container, type ContainerItem } from '../model/containers';
import { PLATFORM_LANE } from '../model/lanes';
import type { Animator } from './animations';
import { Camera, type Point, type Rect } from './Camera';
import { ContainerRenderer } from './ContainerRenderer';
import { EdgeRenderer } from './EdgeRenderer';
import { NodeRenderer } from './NodeRenderer';
import type { Scene, VNode } from './Scene';
import { moduleColor, theme, withAlpha } from './theme';

const GRID_SPACING = 28;

export class Engine {
  readonly camera = new Camera();
  private readonly ctx: CanvasRenderingContext2D;
  private readonly nodeRenderer = new NodeRenderer();
  private readonly edgeRenderer = new EdgeRenderer();
  private readonly containerRenderer = new ContainerRenderer();

  private dirty = true;
  private rafId: number | null = null;
  private dpr = 1;

  /** Extra painting after the world layer (e.g. minimap sync). */
  onAfterRender: (() => void) | null = null;

  liveMode = false;

  /** Module container backdrops — toggled from the toolbar / `m`. */
  showContainers = true;

  /**
   * The in-flight selection rectangle in **world** coordinates, or
   * null. World-space so it stays glued to the cards it covers if the camera
   * moves mid-drag.
   */
  marquee: Rect | null = null;

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly scene: Scene,
    private readonly animator: Animator,
  ) {
    this.ctx = canvas.getContext('2d')!;
    const ro = new ResizeObserver(() => this.resize());
    ro.observe(canvas.parentElement ?? canvas);
    this.resize();
    this.camera.onChange = () => this.requestRender();
  }

  requestRender(): void {
    this.dirty = true;
    if (this.rafId === null) {
      this.rafId = requestAnimationFrame((now) => this.frame(now));
    }
  }

  private resize(): void {
    const parent = this.canvas.parentElement ?? document.body;
    const w = parent.clientWidth || window.innerWidth;
    const h = parent.clientHeight || window.innerHeight;
    this.dpr = Math.max(1, window.devicePixelRatio || 1);
    this.canvas.width = Math.round(w * this.dpr);
    this.canvas.height = Math.round(h * this.dpr);
    this.canvas.style.width = `${w}px`;
    this.canvas.style.height = `${h}px`;
    this.camera.setViewport(w, h);
    this.requestRender();
  }

  private frame(now: number): void {
    this.rafId = null;
    const animating = this.animator.tick(now);
    const flying = this.camera.tick(now);

    if (this.dirty || animating || flying) {
      this.dirty = false;
      this.render();
      this.onAfterRender?.();
    }
    if (animating || flying || this.dirty) {
      this.rafId = requestAnimationFrame((n) => this.frame(n));
    }
  }

  private render(): void {
    const { ctx, camera } = this;
    const w = camera.viewportW;
    const h = camera.viewportH;

    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = theme.bg;
    ctx.fillRect(0, 0, w, h);

    this.drawDotGrid(ctx, w, h);

    ctx.save();
    camera.applyTransform(ctx);

    const viewRect = camera.visibleWorldRect(160);
    const visibleNodes = this.scene.query(viewRect);
    const visibleIds = new Set(visibleNodes.map((v) => v.node.id));

    // Module containers below everything (backdrop).
    if (this.showContainers) this.drawContainers(ctx, viewRect);

    // Edges first (under the cards). An edge is drawn if either endpoint is visible.
    for (const ve of this.scene.edges.values()) {
      const from = this.scene.nodes.get(ve.edge.from);
      const to = this.scene.nodes.get(ve.edge.to);
      if (!from || !to) continue;
      if (!visibleIds.has(from.node.id) && !visibleIds.has(to.node.id)) {
        // quick bbox check: the curve could still cross the viewport
        const minX = Math.min(from.x, to.x);
        const maxX = Math.max(from.x + from.w, to.x + to.w);
        const minY = Math.min(from.y, to.y);
        const maxY = Math.max(from.y + from.h, to.y + to.h);
        if (maxX < viewRect.x || minX > viewRect.x + viewRect.w || maxY < viewRect.y || minY > viewRect.y + viewRect.h) {
          continue;
        }
      }
      this.edgeRenderer.draw(ctx, ve, from, to, {
        zoom: camera.scale,
        highlighted: this.scene.highlightEdgeId === ve.edge.id,
        dim: this.scene.edgeDimFactor(ve),
      });
    }

    // Nodes — the focused one drawn last (on top).
    let selected: VNode | null = null;
    for (const vn of visibleNodes) {
      if (vn.node.id === this.scene.selectedId) {
        selected = vn;
        continue;
      }
      this.drawNode(vn);
    }
    if (selected) this.drawNode(selected);

    if (this.marquee) this.drawMarquee(ctx, this.marquee);

    ctx.restore();
  }

  /** The selection rectangle: faint fill, dashed accent border. */
  private drawMarquee(ctx: CanvasRenderingContext2D, r: Rect): void {
    const scale = this.camera.scale;
    ctx.save();
    ctx.fillStyle = withAlpha(theme.accent, 0.1);
    ctx.fillRect(r.x, r.y, r.w, r.h);
    ctx.strokeStyle = withAlpha(theme.accent, 0.85);
    ctx.lineWidth = 1.5 / scale;
    ctx.setLineDash([6 / scale, 4 / scale]);
    ctx.strokeRect(r.x, r.y, r.w, r.h);
    ctx.restore();
  }

  /**
   * Current module container rects from the live node positions
   * (`model/containers.ts`), excluding the platform lane. Used for both drawing
   * and for hit-testing a container drag.
   */
  containers(): Container[] {
    const items: ContainerItem[] = [];
    for (const vn of this.scene.nodes.values()) {
      if (!vn.lane || vn.lane === PLATFORM_LANE || vn.removing || vn.alpha <= 0.02) continue;
      items.push({ lane: vn.lane, x: vn.x, y: vn.y, w: vn.w, h: vn.h });
    }
    return computeContainers(items, { exclude: [PLATFORM_LANE] });
  }

  /** The lane whose container encloses [p], or null — for grabbing a whole module. */
  containerHit(p: Point): string | null {
    if (!this.showContainers) return null;
    for (const c of this.containers()) {
      if (p.x >= c.x && p.x <= c.x + c.w && p.y >= c.y && p.y <= c.y + c.h) return c.lane;
    }
    return null;
  }

  private drawContainers(ctx: CanvasRenderingContext2D, viewRect: { x: number; y: number; w: number; h: number }): void {
    const containers = this.containers();
    if (containers.length === 0) return;

    const dim = this.scene.selectedId ? 0.45 : 1;
    for (const c of containers) {
      if (c.x + c.w < viewRect.x || c.x > viewRect.x + viewRect.w) continue;
      if (c.y + c.h < viewRect.y || c.y > viewRect.y + viewRect.h) continue;
      this.containerRenderer.draw(ctx, c, {
        color: moduleColor(c.lane),
        zoom: this.camera.scale,
        dim,
        selected: this.scene.selectedLane === c.lane,
      });
    }
  }

  private drawNode(vn: VNode): void {
    this.nodeRenderer.draw(this.ctx, vn, {
      zoom: this.camera.scale,
      selected: this.scene.isSelected(vn.node.id),
      hovered: this.scene.hoverNodeId === vn.node.id,
      liveMode: this.liveMode,
      dim: this.scene.nodeDimFactor(vn.node.id),
    });
  }

  /** Dot-grid backdrop anchored to world space (fades out at low zoom). */
  private drawDotGrid(ctx: CanvasRenderingContext2D, w: number, h: number): void {
    const scale = this.camera.scale;
    const spacingScreen = GRID_SPACING * scale;
    if (spacingScreen < 9) return;

    const alphaFactor = Math.min(1, (spacingScreen - 9) / 18);
    ctx.fillStyle = theme.gridDot;
    ctx.globalAlpha = alphaFactor;

    const topLeft = this.camera.screenToWorld({ x: 0, y: 0 });
    const startX = Math.floor(topLeft.x / GRID_SPACING) * GRID_SPACING;
    const startY = Math.floor(topLeft.y / GRID_SPACING) * GRID_SPACING;
    const r = Math.min(1.4, 1.1 * scale + 0.4);

    for (let wx = startX; ; wx += GRID_SPACING) {
      const sx = (wx - this.camera.x) * scale + w / 2;
      if (sx > w) break;
      if (sx < 0) continue;
      for (let wy = startY; ; wy += GRID_SPACING) {
        const sy = (wy - this.camera.y) * scale + h / 2;
        if (sy > h) break;
        if (sy < 0) continue;
        ctx.beginPath();
        ctx.arc(sx, sy, r, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.globalAlpha = 1;
  }
}
