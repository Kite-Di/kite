/**
 * Legend overlay (toggle with `?` or the toolbar button): explains the visual
 * language of the board — node kinds, scope colors, edge styles, shortcuts.
 */

import { el } from './dom';

const ROWS: [string, string][] = [
  ['◻ class', '@Injectable class'],
  ['ƒ function', '@Provides binding'],
  ['◠ interface', 'bound via bindTo'],
  ['{…} set', 'Set<T> multibinding (@IntoSet contributions)'],
  ['[:] map', 'Map<String, T> multibinding (@IntoMap entries)'],
  ['▶ entry point', 'Activity/Fragment with @Inject fields'],
  ['▢ external', 'provided by the environment (Application, Context)'],
];

const SCOPES: [string, string][] = [
  ['indigo', '@Singleton — app lifetime'],
  ['teal', '@ActivityScoped — survives rotation'],
  ['amber', '@FragmentScoped'],
  ['gray', 'unscoped — new instance per injection'],
];

const EDGES: [string, string][] = [
  ['solid →', 'direct injection (arrow at the consumer)'],
  ['dashed →', 'deferred: Provider<T> / Lazy<T>'],
  ['⚡', 'field injection site'],
];

const KEYS: [string, string][] = [
  ['a', 'arrange — re-run the vertical tree layout (clears pins)'],
  ['/', 'search'],
  ['Shift+1 / Shift+2', 'fit graph / zoom to selection'],
  ['i', 'impact mode — transitive blast radius of the selected node'],
  ['drag a node', 'pin it (layout keeps it in place)'],
  ['?', 'toggle this legend'],
];

export class Legend {
  private readonly rootEl: HTMLElement;
  private shown = false;

  constructor(parent: HTMLElement) {
    this.rootEl = el(
      'div',
      { class: 'legend hidden' },
      el('div', { class: 'legend-title', text: 'Legend' }),
      section('Nodes', ROWS),
      section('Scope chips', SCOPES),
      section('Edges', EDGES),
      section('Keys', KEYS),
    );
    parent.append(this.rootEl);
  }

  get visible(): boolean {
    return this.shown;
  }

  toggle(): void {
    this.shown = !this.shown;
    this.rootEl.classList.toggle('hidden', !this.shown);
  }

  hide(): void {
    this.shown = false;
    this.rootEl.classList.add('hidden');
  }
}

function section(title: string, rows: [string, string][]): HTMLElement {
  return el(
    'div',
    { class: 'legend-section' },
    el('div', { class: 'legend-heading', text: title }),
    ...rows.map(([symbol, meaning]) =>
      el(
        'div',
        { class: 'legend-row' },
        el('span', { class: 'legend-symbol', text: symbol }),
        el('span', { class: 'legend-meaning', text: meaning }),
      ),
    ),
  );
}
