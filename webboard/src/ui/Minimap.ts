/**
 * Corner overview minimap: the whole graph scaled down, the
 * current viewport as a rectangle, click / drag to navigate.
 */

import type { Camera } from '../canvas/Camera';
import type { Scene } from '../canvas/Scene';
import { moduleColor, theme, withAlpha } from '../canvas/theme';
import { el } from './dom';

const MAP_W = 208;
const MAP_H = 140;
const PAD = 10;

export class Minimap {
  readonly root: HTMLElement;
  private readonly canvas: HTMLCanvasElement;
  private readonly ctx: CanvasRenderingContext2D;
  private dragging = false;

  constructor(
    parent: HTMLElement,
    private readonly scene: Scene,
    private readonly camera: Camera,
    private readonly onNavigate: () => void,
  ) {
    this.canvas = el('canvas', { class: 'minimap-canvas' });
    const dpr = Math.max(1, window.devicePixelRatio || 1);
    this.canvas.width = MAP_W * dpr;
    this.canvas.height = MAP_H * dpr;
    this.canvas.style.width = `${MAP_W}px`;
    this.canvas.style.height = `${MAP_H}px`;
    this.ctx = this.canvas.getContext('2d')!;
    this.ctx.scale(dpr, dpr);

    this.root = el('div', { class: 'minimap' }, this.canvas);
    parent.append(this.root);

    this.canvas.addEventListener('pointerdown', (ev) => {
      this.dragging = true;
      this.canvas.setPointerCapture(ev.pointerId);
      this.navigate(ev);
    });
    this.canvas.addEventListener('pointermove', (ev) => {
      if (this.dragging) this.navigate(ev);
    });
    this.canvas.addEventListener('pointerup', () => {
      this.dragging = false;
    });
  }

  private mapTransform(): { scale: number; ox: number; oy: number } | null {
    const b = this.scene.bounds();
    if (!b || b.w <= 0 || b.h <= 0) return null;
    const scale = Math.min((MAP_W - PAD * 2) / b.w, (MAP_H - PAD * 2) / b.h);
    const ox = PAD + ((MAP_W - PAD * 2) - b.w * scale) / 2 - b.x * scale;
    const oy = PAD + ((MAP_H - PAD * 2) - b.h * scale) / 2 - b.y * scale;
    return { scale, ox, oy };
  }

  private navigate(ev: PointerEvent): void {
    const t = this.mapTransform();
    if (!t) return;
    const rect = this.canvas.getBoundingClientRect();
    const mx = ev.clientX - rect.left;
    const my = ev.clientY - rect.top;
    this.camera.x = (mx - t.ox) / t.scale;
    this.camera.y = (my - t.oy) / t.scale;
    this.camera.cancelFlight();
    this.onNavigate();
  }

  render(): void {
    const ctx = this.ctx;
    ctx.clearRect(0, 0, MAP_W, MAP_H);

    const t = this.mapTransform();
    if (!t) {
      this.root.classList.add('hidden');
      return;
    }
    this.root.classList.remove('hidden');

    // nodes
    for (const vn of this.scene.nodes.values()) {
      if (vn.alpha <= 0.05) continue;
      const dim = this.scene.nodeDimFactor(vn.node.id);
      ctx.fillStyle = withAlpha(moduleColor(vn.node.providedBy?.gradleModule), 0.35 + 0.5 * dim);
      ctx.fillRect(t.ox + vn.x * t.scale, t.oy + vn.y * t.scale, Math.max(2, vn.w * t.scale), Math.max(1.5, vn.h * t.scale));
    }

    // viewport rectangle
    const view = this.camera.visibleWorldRect();
    ctx.strokeStyle = theme.selection;
    ctx.lineWidth = 1.25;
    ctx.strokeRect(t.ox + view.x * t.scale, t.oy + view.y * t.scale, view.w * t.scale, view.h * t.scale);
  }
}
