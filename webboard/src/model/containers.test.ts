import { describe, expect, it } from 'vitest';
import { computeContainers, type ContainerItem } from './containers';

const item = (lane: string, x: number, y: number): ContainerItem => ({ lane, x, y, w: 100, h: 40 });

describe('computeContainers', () => {
  it('wraps each lane in a padded box that encloses all its nodes', () => {
    const containers = computeContainers(
      [item('a', 0, 0), item('a', 200, 100), item('b', 500, 0)],
      { pad: 10, header: 20 },
    );
    const a = containers.find((c) => c.lane === 'a')!;
    // tight box is (0,0)..(300,140); pad 10 on all sides; header 20 extra on top
    expect(a.x).toBe(-10);
    expect(a.y).toBe(-30);
    expect(a.x + a.w).toBe(310);
    expect(a.y + a.h).toBe(150);
    // every node of the lane sits inside its container
    for (const n of [item('a', 0, 0), item('a', 200, 100)]) {
      expect(n.x).toBeGreaterThanOrEqual(a.x);
      expect(n.y).toBeGreaterThanOrEqual(a.y);
      expect(n.x + n.w).toBeLessThanOrEqual(a.x + a.w);
      expect(n.y + n.h).toBeLessThanOrEqual(a.y + a.h);
    }
  });

  it('returns lanes in a deterministic (sorted) order', () => {
    const containers = computeContainers([item('zeta', 0, 0), item('alpha', 10, 10), item('mu', 20, 20)]);
    expect(containers.map((c) => c.lane)).toEqual(['alpha', 'mu', 'zeta']);
  });

  it('draws nothing when fewer than two lanes are present', () => {
    expect(computeContainers([item('solo', 0, 0), item('solo', 10, 10)])).toEqual([]);
    expect(computeContainers([])).toEqual([]);
  });

  it('excludes the platform lane (environment, not a module)', () => {
    const containers = computeContainers(
      [item('app', 0, 0), item('core', 100, 0), item('platform', 200, 0)],
      { exclude: ['platform'] },
    );
    expect(containers.map((c) => c.lane).sort()).toEqual(['app', 'core']);
  });

  it('collapses to nothing if excluding leaves a single lane', () => {
    expect(
      computeContainers([item('app', 0, 0), item('platform', 200, 0)], { exclude: ['platform'] }),
    ).toEqual([]);
  });
});
