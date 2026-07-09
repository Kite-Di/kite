/**
 * Tween helpers: node enter fade+scale (30 ms stagger), edge
 * dash-draw (300 ms), update pulse, fade-out on remove, camera flights and
 * position interpolation all run through one Animator that the Engine ticks.
 */

export type Ease = (t: number) => number;

export const linear: Ease = (t) => t;
export const easeOutCubic: Ease = (t) => 1 - Math.pow(1 - t, 3);
export const easeInOutCubic: Ease = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
/** Slight overshoot — used for node enter scale. */
export const easeOutBack: Ease = (t) => {
  const c1 = 1.20158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
};

export interface TweenOpts {
  duration: number;
  delay?: number;
  ease?: Ease;
  /** t is eased progress in [0, 1]. */
  update: (t: number) => void;
  done?: () => void;
}

interface ActiveTween extends TweenOpts {
  start: number;
  finished: boolean;
}

/** Central animation clock. `tick` returns true while anything is animating. */
export class Animator {
  private tweens = new Set<ActiveTween>();

  add(opts: TweenOpts): () => void {
    const tw: ActiveTween = { ease: easeOutCubic, delay: 0, ...opts, start: performance.now(), finished: false };
    this.tweens.add(tw);
    return () => {
      tw.finished = true;
      this.tweens.delete(tw);
    };
  }

  get active(): boolean {
    return this.tweens.size > 0;
  }

  tick(now: number): boolean {
    for (const tw of [...this.tweens]) {
      const elapsed = now - tw.start - (tw.delay ?? 0);
      if (elapsed < 0) continue;
      const raw = Math.min(1, tw.duration <= 0 ? 1 : elapsed / tw.duration);
      tw.update((tw.ease ?? easeOutCubic)(raw));
      if (raw >= 1 && !tw.finished) {
        tw.finished = true;
        this.tweens.delete(tw);
        tw.done?.();
      }
    }
    return this.tweens.size > 0;
  }

  clear(): void {
    this.tweens.clear();
  }
}

/** Interpolation helper. */
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;

// ---- Domain-specific tween recipes (operate on the mutable view-model) ----

export interface EnterAnimatable {
  alpha: number;
  scale: number;
}

/** Node enter: fade in + scale 0.8→1.0, staggered 30 ms per index. */
export function nodeEnter(animator: Animator, target: EnterAnimatable, index: number, onFrame: () => void): void {
  target.alpha = 0;
  target.scale = 0.8;
  animator.add({
    duration: 320,
    delay: index * 30,
    ease: easeOutBack,
    update: (t) => {
      target.alpha = Math.min(1, t * 1.4);
      target.scale = lerp(0.8, 1.0, t);
      onFrame();
    },
  });
}

export interface DrawAnimatable {
  alpha: number;
  drawProgress: number;
}

/** Edge enter: 300 ms stroke-dash draw from provider to consumer. */
export function edgeDraw(animator: Animator, target: DrawAnimatable, index: number, onFrame: () => void): void {
  target.alpha = 1;
  target.drawProgress = 0;
  animator.add({
    duration: 300,
    delay: index * 30,
    ease: easeInOutCubic,
    update: (t) => {
      target.drawProgress = t;
      onFrame();
    },
  });
}

export interface PulseAnimatable {
  pulse: number;
}

/** Update pulse: a ring flash that decays over ~600 ms. */
export function pulse(animator: Animator, target: PulseAnimatable, onFrame: () => void): void {
  animator.add({
    duration: 600,
    ease: linear,
    update: (t) => {
      target.pulse = 1 - t;
      onFrame();
    },
    done: () => {
      target.pulse = 0;
      onFrame();
    },
  });
}

export interface FadeAnimatable {
  alpha: number;
}

/** Fade-out before removal; resolves the removal in `done`. */
export function fadeOut(animator: Animator, target: FadeAnimatable, onFrame: () => void, done: () => void): void {
  const from = target.alpha;
  animator.add({
    duration: 250,
    ease: easeOutCubic,
    update: (t) => {
      target.alpha = lerp(from, 0, t);
      onFrame();
    },
    done,
  });
}

export interface GlowAnimatable {
  glow: number;
}

/** Runtime badge glow on instanceCreated. */
export function glowFlash(animator: Animator, target: GlowAnimatable, onFrame: () => void): void {
  animator.add({
    duration: 900,
    ease: easeOutCubic,
    update: (t) => {
      target.glow = 1 - t;
      onFrame();
    },
    done: () => {
      target.glow = 0;
      onFrame();
    },
  });
}

export interface ShakeAnimatable {
  shake: number;
  errorHalo: number;
}

/** Red halo + shake on runtime.resolutionFailed. */
export function errorShake(animator: Animator, target: ShakeAnimatable, onFrame: () => void): void {
  target.errorHalo = 1;
  animator.add({
    duration: 700,
    ease: linear,
    update: (t) => {
      target.shake = Math.sin(t * Math.PI * 8) * (1 - t) * 5;
      onFrame();
    },
    done: () => {
      target.shake = 0;
      onFrame();
    },
  });
}

export interface PositionAnimatable {
  x: number;
  y: number;
}

/** Full-relayout position interpolation: tween from old to new over 400 ms. */
export function moveTo(
  animator: Animator,
  target: PositionAnimatable,
  to: { x: number; y: number },
  onFrame: () => void,
  duration = 400,
): void {
  const fx = target.x;
  const fy = target.y;
  if (Math.abs(fx - to.x) < 0.5 && Math.abs(fy - to.y) < 0.5) {
    target.x = to.x;
    target.y = to.y;
    return;
  }
  animator.add({
    duration,
    ease: easeInOutCubic,
    update: (t) => {
      target.x = lerp(fx, to.x, t);
      target.y = lerp(fy, to.y, t);
      onFrame();
    },
  });
}
