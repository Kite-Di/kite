/**
 * Canvas design tokens — dark developer-tool theme shared by the renderers
 * and the minimap. (DOM styling lives in styles.css; these are the values
 * the Canvas 2D code needs programmatically.)
 */

export const theme = {
  bg: '#0f1115',
  gridDot: 'rgba(148, 163, 184, 0.16)',

  cardBg: '#1a1f2b',
  cardBgLod: '#232a3a',
  cardBorder: '#2a3142',
  cardShadow: 'rgba(0, 0, 0, 0.45)',
  text: '#e6e9f0',
  textMuted: '#8b93a7',

  accent: '#6c8cff',
  selection: '#8aa4ff',
  green: '#34d399',
  red: '#f87171',
  amber: '#fbbf24',

  edge: 'rgba(122, 135, 165, 0.55)',
  edgeHighlight: '#8aa4ff',
  /** Derived `binds` edges: interface satellite → implementation (violet). */
  edgeBinds: 'rgba(192, 132, 252, 0.6)',
  /** Derived `viewModel` edges: screen → ViewModel (teal). */
  edgeViewModel: 'rgba(45, 212, 191, 0.6)',

  font: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
} as const;

/** Scope chip colors. */
export function scopeColor(scope: string | null | undefined): string {
  switch (scope ?? null) {
    case 'Singleton':
      return '#818cf8'; // indigo
    case 'ActivityScoped':
      return '#2dd4bf'; // teal
    case 'FragmentScoped':
      return '#fbbf24'; // amber
    case null:
      return '#9ca3af'; // unscoped gray
    default:
      return '#c084fc'; // custom purple
  }
}

/** Stable per-Gradle-module hue for the card's left border. */
const MODULE_PALETTE = [
  '#6c8cff', // indigo-blue
  '#2dd4bf', // teal
  '#f472b6', // pink
  '#fbbf24', // amber
  '#34d399', // emerald
  '#c084fc', // violet
  '#38bdf8', // sky
  '#fb923c', // orange
  '#a3e635', // lime
  '#f87171', // red
] as const;

export function moduleColor(gradleModule: string | null | undefined): string {
  if (!gradleModule) return '#4b5563';
  let hash = 5381;
  for (let i = 0; i < gradleModule.length; i++) {
    hash = ((hash << 5) + hash + gradleModule.charCodeAt(i)) | 0;
  }
  return MODULE_PALETTE[Math.abs(hash) % MODULE_PALETTE.length]!;
}

/** hex → rgba() with alpha. */
export function withAlpha(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
