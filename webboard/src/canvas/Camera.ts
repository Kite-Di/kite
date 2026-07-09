/**
 * World↔screen transform owner. Center-anchored: (x, y) is the
 * world point at the viewport center; `scale` is world→screen zoom.
 * Supports pan drag, wheel zoom-to-cursor, fit-to-bounds and animated flights.
 */

import { easeInOutCubic, lerp } from './animations';

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Point {
  x: number;
  y: number;
}

const MIN_SCALE = 0.05;
const MAX_SCALE = 3.5;

interface Flight {
  fromX: number;
  fromY: number;
  fromScale: number;
  toX: number;
  toY: number;
  toScale: number;
  start: number;
  duration: number;
}

export class Camera {
  x = 0;
  y = 0;
  scale = 1;
  viewportW = 1;
  viewportH = 1;

  private flight: Flight | null = null;
  onChange: (() => void) | null = null;

  setViewport(w: number, h: number): void {
    this.viewportW = Math.max(1, w);
    this.viewportH = Math.max(1, h);
  }

  worldToScreen(p: Point): Point {
    return {
      x: (p.x - this.x) * this.scale + this.viewportW / 2,
      y: (p.y - this.y) * this.scale + this.viewportH / 2,
    };
  }

  screenToWorld(p: Point): Point {
    return {
      x: (p.x - this.viewportW / 2) / this.scale + this.x,
      y: (p.y - this.viewportH / 2) / this.scale + this.y,
    };
  }

  /** Applies the world transform; caller has already scaled for DPR. */
  applyTransform(ctx: CanvasRenderingContext2D): void {
    ctx.translate(this.viewportW / 2, this.viewportH / 2);
    ctx.scale(this.scale, this.scale);
    ctx.translate(-this.x, -this.y);
  }

  visibleWorldRect(marginPx = 0): Rect {
    const tl = this.screenToWorld({ x: -marginPx, y: -marginPx });
    const br = this.screenToWorld({ x: this.viewportW + marginPx, y: this.viewportH + marginPx });
    return { x: tl.x, y: tl.y, w: br.x - tl.x, h: br.y - tl.y };
  }

  /** Pan by a screen-space delta (drag). */
  panBy(dxScreen: number, dyScreen: number): void {
    this.cancelFlight();
    this.x -= dxScreen / this.scale;
    this.y -= dyScreen / this.scale;
    this.onChange?.();
  }

  /** Zoom keeping the world point under the cursor stationary. */
  zoomAt(screenX: number, screenY: number, factor: number): void {
    this.cancelFlight();
    const before = this.screenToWorld({ x: screenX, y: screenY });
    this.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, this.scale * factor));
    const after = this.screenToWorld({ x: screenX, y: screenY });
    this.x += before.x - after.x;
    this.y += before.y - after.y;
    this.onChange?.();
  }

  private poseForBounds(bounds: Rect, paddingPx: number): { x: number; y: number; scale: number } {
    const w = Math.max(1, bounds.w);
    const h = Math.max(1, bounds.h);
    const scale = Math.min(
      MAX_SCALE,
      Math.max(
        MIN_SCALE,
        Math.min((this.viewportW - paddingPx * 2) / w, (this.viewportH - paddingPx * 2) / h),
      ),
    );
    return { x: bounds.x + bounds.w / 2, y: bounds.y + bounds.h / 2, scale: Math.min(scale, 1.25) };
  }

  /** Jump-fit the viewport to bounds (Shift+1). */
  fitBounds(bounds: Rect, paddingPx = 80): void {
    this.cancelFlight();
    const pose = this.poseForBounds(bounds, paddingPx);
    this.x = pose.x;
    this.y = pose.y;
    this.scale = pose.scale;
    this.onChange?.();
  }

  /** Animated camera flight to frame the given bounds. */
  flyToBounds(bounds: Rect, paddingPx = 120, duration = 500): void {
    const pose = this.poseForBounds(bounds, paddingPx);
    this.flyTo(pose.x, pose.y, pose.scale, duration);
  }

  flyTo(x: number, y: number, scale: number, duration = 500): void {
    this.flight = {
      fromX: this.x,
      fromY: this.y,
      fromScale: this.scale,
      toX: x,
      toY: y,
      toScale: Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale)),
      start: performance.now(),
      duration,
    };
  }

  cancelFlight(): void {
    this.flight = null;
  }

  /** Advances an in-progress flight. Returns true while animating. */
  tick(now: number): boolean {
    const f = this.flight;
    if (!f) return false;
    const t = Math.min(1, (now - f.start) / f.duration);
    const e = easeInOutCubic(t);
    this.x = lerp(f.fromX, f.toX, e);
    this.y = lerp(f.fromY, f.toY, e);
    this.scale = lerp(f.fromScale, f.toScale, e);
    if (t >= 1) this.flight = null;
    this.onChange?.();
    return this.flight !== null;
  }
}
