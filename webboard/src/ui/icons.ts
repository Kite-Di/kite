/**
 * Line icons for the board's chrome — 24-unit strokes for the navbar, menus and
 * buttons, plus the node-kind glyphs the canvas draws, for the details panel.
 */

import type { NodeKind } from '../model/graph';

const STROKE = 'fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"';

const PATHS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  graph: '<circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="6" r="2.5"/><circle cx="12" cy="18" r="2.5"/><path d="M7.5 8 11 15.5M16.5 8 13 15.5"/>',
  decision: '<path d="M6 3v6a6 6 0 0 0 6 6 6 6 0 0 1 6 6M18 3v6"/>',
  runtime: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  folder: '<path d="M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z"/>',
  download: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
  legend: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17.5v.01"/>',
  chevron: '<path d="m6 9 6 6 6-6"/>',
  updown: '<path d="m7 15 5 5 5-5M7 9l5-5 5 5"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/>',
  plus: '<path d="M12 6v12M6 12h12"/>',
  minus: '<path d="M6 12h12"/>',
  fit: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  warning: '<path d="M12 4 2.5 20h19Z"/><path d="M12 10v4M12 17.5v.01"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  theme: '<circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 0 0 18Z" fill="currentColor"/>',
} as const;

export type IconName = keyof typeof PATHS;

export function iconSvg(name: IconName): string {
  return `<svg class="i" viewBox="0 0 24 24" ${STROKE} aria-hidden="true">${PATHS[name]}</svg>`;
}

/** The node-kind glyphs, matching what the canvas draws in a card's tile. */
const KIND_PATHS: Record<NodeKind, string> = {
  injectable: '<rect x="2" y="2" width="10" height="10" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.4"/><circle cx="7" cy="7" r="1.7" fill="currentColor"/>',
  boundInterface: '<path d="M10.3 3.3A5 5 0 1 0 10.3 10.7" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M8.5 4.5 11.5 7 8.5 9.5" fill="none" stroke="currentColor" stroke-width="1.4"/>',
  entryPoint: '<circle cx="7" cy="7" r="5.5" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M5.6 4.4 9.6 7 5.6 9.6Z" fill="currentColor"/>',
  set: '<text x="7" y="10.5" text-anchor="middle" font-size="10.5" font-weight="700" fill="currentColor">{ }</text>',
  map: '<text x="7" y="10.5" text-anchor="middle" font-size="10.5" font-weight="700" fill="currentColor">[ ]</text>',
  supplied: '<rect x="1.5" y="3" width="7.5" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M9.5 7h3M11 5.3 12.6 7 11 8.7" fill="none" stroke="currentColor" stroke-width="1.4"/>',
  provides: '<text x="7" y="10.5" text-anchor="middle" font-size="11" font-weight="600" fill="currentColor">ƒ</text>',
  external: '<rect x="2" y="2" width="10" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="1.4" stroke-dasharray="2.5 2"/>',
};

export function kindSvg(kind: NodeKind): string {
  return `<svg viewBox="0 0 14 14" aria-hidden="true">${KIND_PATHS[kind]}</svg>`;
}
