import { describe, expect, it } from 'vitest';
import { PLATFORM_LANE } from '../model/lanes';
import { assignModuleColors, moduleColor } from './theme';

// The demo app's modules: a plain name hash put :core:analytics and
// :feature:orders:impl on the same blue, and :core:network and
// :feature:profile:impl on the same lime — two boxes reading as one module.
const DEMO = [':app', ':core:analytics', ':core:network', ':feature:orders:impl', ':feature:profile:impl'];

describe('moduleColor', () => {
  it('gives every module of the graph its own colour', () => {
    assignModuleColors(DEMO);
    expect(new Set(DEMO.map(moduleColor)).size).toBe(DEMO.length);
  });

  it('keeps a module on its colour when the graph gains another module', () => {
    assignModuleColors(DEMO);
    const before = moduleColor(':app');
    assignModuleColors([...DEMO, ':feature:search:impl']);
    expect(moduleColor(':app')).toBe(before);
  });

  it('paints graph arguments and platform types in no module colour', () => {
    assignModuleColors(DEMO);
    const platform = moduleColor(PLATFORM_LANE);
    expect(DEMO.map(moduleColor)).not.toContain(platform);
    expect(platform).toBe(moduleColor(null));
  });
});
