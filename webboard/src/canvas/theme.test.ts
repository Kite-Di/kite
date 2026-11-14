import { afterEach, describe, expect, it } from 'vitest';
import { PLATFORM_LANE } from '../model/lanes';
import { assignModuleColors, moduleColor, parseThemeChoice, resolveThemeMode, scopeColor, setThemeMode, theme, type ThemeMode } from './theme';

afterEach(() => setThemeMode('light'));

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

  it('keeps every module distinct, and on its own slot, in the dark theme too', () => {
    assignModuleColors(DEMO);
    const light = DEMO.map(moduleColor);
    setThemeMode('dark');
    const dark = DEMO.map(moduleColor);
    expect(new Set(dark).size).toBe(DEMO.length);
    expect(dark).not.toEqual(light); // lifted for a dark canvas, not reused as-is
  });
});

describe('setThemeMode', () => {
  const HEX = /^#[0-9a-f]{6}$/i;

  // withAlpha parses #rrggbb; an rgba() or a 3-digit hex here renders as NaN — black.
  it.each<ThemeMode>(['light', 'dark'])('keeps every colour the renderers fade in %s as #rrggbb', (mode) => {
    setThemeMode(mode);
    const faded = [theme.accent, theme.selection, theme.green, theme.red, theme.amber, theme.textMuted, theme.edgeLod, theme.edgeHighlight];
    const scopes = ['Singleton', 'ActivityScoped', 'FragmentScoped', null, 'Session'].map(scopeColor);
    assignModuleColors(DEMO);
    for (const c of [...faded, ...scopes, ...DEMO.map(moduleColor), moduleColor(PLATFORM_LANE)]) expect(c).toMatch(HEX);
  });

  it('swaps the palette in place, so renderers holding `theme` see the change', () => {
    const held = theme;
    setThemeMode('dark');
    expect(held.bg).toBe('#1d1f27');
    setThemeMode('light');
    expect(held.bg).toBe('#f3f4ef');
  });
});

describe('theme choice', () => {
  it('follows the system until the developer picks a side', () => {
    expect(resolveThemeMode('system', true)).toBe('dark');
    expect(resolveThemeMode('system', false)).toBe('light');
    expect(resolveThemeMode('light', true)).toBe('light');
    expect(resolveThemeMode('dark', false)).toBe('dark');
  });

  it('reads back only the three choices it stores, anything else is system', () => {
    expect(parseThemeChoice('dark')).toBe('dark');
    expect(parseThemeChoice('light')).toBe('light');
    expect(parseThemeChoice('system')).toBe('system');
    expect(parseThemeChoice(null)).toBe('system');
    expect(parseThemeChoice('sepia')).toBe('system');
  });
});
