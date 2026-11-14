/**
 * Legend overlay (toggle with `?` or the toolbar button): explains the visual
 * language of the board — node kinds, scope colors, edge styles, shortcuts.
 */

import { el } from './dom';

// The kinds the processor emits today. `provides` and `map` stay in the schema for
// v1 snapshots only, so they are not explained here.
const ROWS: [string, string][] = [
  ['◻ class', 'a class in the graph — constructed from its constructor'],
  ['◠ interface', 'an interface, bound to the one class that implements it (or the @Bind choice)'],
  ['⇥ supplied', 'a type you do not own, built by a class implementing Supplies<T>'],
  ['{…} set', 'Set<T> — every implementation of T'],
  ['▶ entry point', 'a ViewModel: androidx keeps it, the graph fills its constructor'],
  ['▢ external', 'from outside the graph: Application, Context, and Graph.start arguments'],
];

const SCOPES: [string, string][] = [
  ['neutral tag', 'Singleton — the default; one shared instance app-wide'],
  ['green tag', 'ActivityScoped — survives rotation'],
  ['amber tag', 'FragmentScoped'],
  ['purple tag', 'a lifetime of your own, such as Session'],
  ['faint tag', 'unscoped — @Fresh: a new instance per injection'],
];

const CONTAINERS: [string, string][] = [
  ['tinted box', 'one module (or package) — its colour also tints each card’s icon tile'],
  ['amber ring', 'the class a blocked build is asking about'],
];

/** Live-mode badges (top-right of a card). */
const BADGES: [string, string][] = [
  ['● green N', 'instances alive now — a scope holds them; drops when it closes'],
  ['◌ amber N', 'built N times — unscoped, nothing caches it, so nothing is "alive"'],
  ['· hollow', 'never instantiated yet'],
];

const EDGES: [string, string][] = [
  ['solid →', 'direct injection (arrow at the consumer)'],
  ['dashed →', 'deferred: Provider<T> / Lazy<T>'],
  ['⚡', 'field injection site'],
  ['dotted ▷', 'interface realized by its implementation (purple)'],
  ['−·− →', 'screen resolves a ViewModel — appears live (teal)'],
];

const KEYS: [string, string][] = [
  ['a', 'arrange — re-run the vertical tree layout (clears pins)'],
  ['⌘/Ctrl+Z · ⇧⌘/Ctrl+Y', 'undo / redo a move or an arrange'],
  ['/', 'search'],
  ['Shift+1 / Shift+2', 'fit graph / zoom to selection'],
  ['i', 'impact mode — transitive blast radius of the selected node'],
  ['m', 'toggle the module boxes'],
  ['drag empty space', 'selection rectangle — picks every block it touches'],
  ['space + drag / middle mouse', 'pan the board'],
  ['drag a node', 'pin it where you drop it (survives rebuilds); a picked group moves together'],
  ['click a module', "select it — then dragging the container moves the whole module"],
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
      section('Grouping', CONTAINERS),
      section('Runtime badges', BADGES),
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
