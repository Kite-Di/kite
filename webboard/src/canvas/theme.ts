/**
 * Canvas design tokens — PostHog's "3000" neutrals with Kite's blue, light and dark, shared by the
 * renderers and the minimap. (DOM styling lives in styles.css under the same
 * names; these are the values the Canvas 2D code needs programmatically.)
 *
 * The board follows the system theme the way PostHog does: `setThemeMode` swaps
 * the palette in place, so every renderer reads the current one on its next frame.
 * Colours anything passes to `withAlpha` stay 6-digit hex.
 */

import { PLATFORM_LANE } from '../model/lanes';

export type ThemeMode = 'light' | 'dark';

const LIGHT = {
  bg: '#f3f4ef',
  gridDot: 'rgba(17, 17, 17, 0.13)',

  cardBg: '#ffffff',
  cardBgLod: '#e4e5de',
  cardBorder: '#dadbd2',
  cardBorderHover: '#c1c2b9',
  /** The solid 3 px shelf under a card — PostHog's elevation, not a blur. */
  cardShelf: '#dadbd2',
  text: '#111111',
  textMuted: '#6f706a',

  // Kite's own blue (the kite's face) — focus, selection, links. PostHog's
  // layout and neutrals, Kite's accent.
  accent: '#0369fc',
  selection: '#0369fc',
  green: '#388600',
  red: '#db3707',
  amber: '#e09423',

  edge: 'rgba(17, 17, 17, 0.3)',
  edgeLod: '#8f9089',
  edgeHighlight: '#0369fc',
  /** Derived `binds` edges: interface satellite → implementation. */
  edgeBinds: 'rgba(98, 29, 166, 0.55)',
  /** Derived `viewModel` edges: screen → ViewModel. */
  edgeViewModel: 'rgba(66, 130, 126, 0.75)',

  // Scope tags speak LemonTag: neutral for the default lifetime, then success,
  // warning and completion — kept apart from the module hues on purpose.
  scopeSingleton: '#55564f',
  scopeActivity: '#388600',
  scopeFragment: '#894b00',
  scopeNone: '#8f9089',
  scopeCustom: '#621da6',
  noModule: '#9a9b94',

  font: "-apple-system, BlinkMacSystemFont, Inter, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif",
};

type Palette = typeof LIGHT;

const DARK: Palette = {
  bg: '#1d1f27',
  gridDot: 'rgba(255, 255, 255, 0.09)',

  cardBg: '#262830',
  cardBgLod: '#2e3039',
  cardBorder: '#35373e',
  cardBorderHover: '#4a4c52',
  cardShelf: '#35373e',
  text: '#ffffff',
  textMuted: '#8e8f93',

  accent: '#4cb8ff',
  selection: '#4cb8ff',
  green: '#6fb83f',
  red: '#f0602f',
  amber: '#f7a503',

  edge: 'rgba(255, 255, 255, 0.28)',
  edgeLod: '#8e8f93',
  edgeHighlight: '#4cb8ff',
  edgeBinds: 'rgba(165, 110, 255, 0.6)',
  edgeViewModel: 'rgba(48, 213, 200, 0.6)',

  scopeSingleton: '#b4b5ba',
  scopeActivity: '#7cc242',
  scopeFragment: '#f7a503',
  scopeNone: '#8e8f93',
  scopeCustom: '#b994ff',
  noModule: '#6b6d74',

  font: LIGHT.font,
};

/** The palette in force. Mutated in place by `setThemeMode`; read it per frame. */
export const theme: Palette = { ...LIGHT };

let mode: ThemeMode = 'light';

export function setThemeMode(next: ThemeMode): void {
  mode = next;
  Object.assign(theme, next === 'dark' ? DARK : LIGHT);
}

export function themeMode(): ThemeMode {
  return mode;
}

/** What the developer picked in the navbar: follow the system, or a fixed side. */
export type ThemeChoice = 'system' | ThemeMode;

export function resolveThemeMode(choice: ThemeChoice, systemDark: boolean): ThemeMode {
  if (choice === 'system') return systemDark ? 'dark' : 'light';
  return choice;
}

/** A stored choice back from storage — anything unrecognised means "follow the system". */
export function parseThemeChoice(raw: string | null): ThemeChoice {
  return raw === 'light' || raw === 'dark' ? raw : 'system';
}

/** Scope chip colors. */
export function scopeColor(scope: string | null | undefined): string {
  switch (scope ?? null) {
    case 'Singleton':
      return theme.scopeSingleton;
    case 'ActivityScoped':
      return theme.scopeActivity;
    case 'FragmentScoped':
      return theme.scopeFragment;
    case null:
      return theme.scopeNone;
    default:
      return theme.scopeCustom;
  }
}

/**
 * Per-module accent — PostHog's insight series colours. The dark set is the same
 * hues lifted for a dark canvas, slot for slot, so a module keeps its colour when
 * the theme flips.
 */
const MODULE_PALETTE: Record<ThemeMode, readonly string[]> = {
  light: ['#b64b02', '#a56eff', '#529a0a', '#f14f58', '#e4a604', '#0476fb', '#fe729e', '#35416b', '#42827e', '#ce0e74'],
  dark: ['#e07b2e', '#b994ff', '#7cc242', '#ff7a80', '#f7c948', '#4da3ff', '#ff9bbb', '#8d9bd0', '#41cbc4', '#f0569f'],
};
const SLOTS = MODULE_PALETTE.light.length;

let moduleSlots = new Map<string, number>();

function paletteSlot(gradleModule: string): number {
  let hash = 5381;
  for (let i = 0; i < gradleModule.length; i++) {
    hash = ((hash << 5) + hash + gradleModule.charCodeAt(i)) | 0;
  }
  return Math.abs(hash) % SLOTS;
}

/**
 * Gives every module of the graph its own colour. A bare name hash put
 * :core:analytics and :feature:orders:impl on the same blue, so two module boxes
 * read as one. Each module still starts at its hash slot — so it keeps its colour
 * across rebuilds — and only a collision walks on to the next free slot.
 */
export function assignModuleColors(modules: Iterable<string>): void {
  const next = new Map<string, number>();
  const taken = new Set<number>();
  const names = [...new Set(modules)].filter((m) => m !== PLATFORM_LANE).sort((a, b) => a.localeCompare(b));
  for (const m of names) {
    let slot = paletteSlot(m);
    for (let i = 0; i < SLOTS && taken.has(slot); i++) slot = (slot + 1) % SLOTS;
    taken.add(slot);
    next.set(m, slot);
  }
  moduleSlots = next;
}

export function moduleColor(gradleModule: string | null | undefined): string {
  if (!gradleModule || gradleModule === PLATFORM_LANE) return theme.noModule;
  return MODULE_PALETTE[mode][moduleSlots.get(gradleModule) ?? paletteSlot(gradleModule)]!;
}

/** hex → rgba() with alpha. */
export function withAlpha(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
