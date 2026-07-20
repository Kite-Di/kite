/**
 * Toolbar: fit (Shift+1), zoom-to-selection (Shift+2), search
 * ('/' focuses; results dropdown; Enter flies to the node), filter chips by
 * scope / kind / module, and the connection status indicator
 * (live green · disconnected amber · static gray).
 */

import type { GraphNode, GraphSnapshot, NodeKind } from '../model/graph';
import { moduleColor, scopeColor, withAlpha } from '../canvas/theme';
import { clear, el } from './dom';

export type BoardStatus = 'live' | 'connecting' | 'disconnected' | 'static';

export interface FilterState {
  /** Empty set = filter dimension inactive. `null` entry = unscoped. */
  scopes: Set<string | null>;
  kinds: Set<NodeKind>;
  modules: Set<string>;
}

export function emptyFilter(): FilterState {
  return { scopes: new Set(), kinds: new Set(), modules: new Set() };
}

export function filterActive(f: FilterState): boolean {
  return f.scopes.size > 0 || f.kinds.size > 0 || f.modules.size > 0;
}

export function nodePassesFilter(node: GraphNode, f: FilterState): boolean {
  if (f.scopes.size > 0 && !f.scopes.has(node.scope ?? null)) return false;
  if (f.kinds.size > 0 && !f.kinds.has(node.kind)) return false;
  if (f.modules.size > 0 && !f.modules.has(node.providedBy?.gradleModule ?? '')) return false;
  return true;
}

export interface ToolbarCallbacks {
  onFit: () => void;
  onZoomSelection: () => void;
  onSelectNode: (nodeId: string) => void;
  onFilterChange: (filter: FilterState) => void;
  onOpenFile: () => void;
  onExportPng: () => void;
  onLegend: () => void;
  onArrange: () => void;
}

const STATUS_LABEL: Record<BoardStatus, string> = {
  live: 'live',
  connecting: 'connecting…',
  disconnected: 'disconnected',
  static: 'static',
};

const ALL_KINDS: NodeKind[] = ['injectable', 'provides', 'boundInterface', 'entryPoint', 'set', 'map', 'external'];

export class Toolbar {
  readonly root: HTMLElement;
  private readonly searchInput: HTMLInputElement;
  private readonly searchResults: HTMLElement;
  private readonly statusDot: HTMLElement;
  private readonly statusLabel: HTMLElement;
  private readonly appLabel: HTMLElement;
  private readonly filterPopover: HTMLElement;
  private readonly filterBtn: HTMLElement;

  private snapshot: GraphSnapshot | null = null;
  private filter: FilterState = emptyFilter();
  private results: GraphNode[] = [];
  private activeIndex = -1;

  constructor(
    parent: HTMLElement,
    private readonly cb: ToolbarCallbacks,
  ) {
    this.searchInput = el('input', { class: 'search-input', type: 'text', placeholder: 'Search nodes…  ( / )' });
    this.searchResults = el('div', { class: 'search-results hidden' });
    this.statusDot = el('span', { class: 'status-dot status-static' });
    this.statusLabel = el('span', { class: 'status-label', text: 'static' });
    this.appLabel = el('span', { class: 'app-label muted', text: '' });
    this.filterPopover = el('div', { class: 'filter-popover hidden' });
    this.filterBtn = el('button', {
      class: 'tb-btn',
      text: 'Filters',
      title: 'Filter by scope / kind / module',
      onClick: () => this.toggleFilters(),
    });

    this.root = el(
      'header',
      { class: 'toolbar' },
      el(
        'div',
        { class: 'tb-left' },
        el('span', { class: 'brand' }, el('span', { class: 'brand-mark', text: '◉' }), el('span', { text: 'Kite Board' })),
        this.appLabel,
      ),
      el('div', { class: 'tb-center' }, el('div', { class: 'search-wrap' }, this.searchInput, this.searchResults)),
      el(
        'div',
        { class: 'tb-right' },
        this.filterBtn,
        el('button', {
          class: 'tb-btn tb-btn-primary',
          text: 'Arrange',
          title: 'Auto-arrange all blocks into the vertical dependency tree (a) — clears pins',
          onClick: () => cb.onArrange(),
        }),
        el('button', { class: 'tb-btn', text: 'Fit', title: 'Fit graph (Shift+1)', onClick: () => cb.onFit() }),
        el('button', { class: 'tb-btn', text: 'Selection', title: 'Zoom to selection (Shift+2)', onClick: () => cb.onZoomSelection() }),
        el('button', { class: 'tb-btn', text: 'Open…', title: 'Open a graph.json', onClick: () => cb.onOpenFile() }),
        el('button', { class: 'tb-btn', text: 'PNG', title: 'Export the current view as PNG', onClick: () => cb.onExportPng() }),
        el('button', { class: 'tb-btn', text: '?', title: 'Legend (?)', onClick: () => cb.onLegend() }),
        el('span', { class: 'status' }, this.statusDot, this.statusLabel),
      ),
      this.filterPopover,
    );
    parent.append(this.root);

    this.wireSearch();
    document.addEventListener('click', (ev) => {
      if (!this.filterPopover.classList.contains('hidden') && !this.filterPopover.contains(ev.target as Node) && ev.target !== this.filterBtn) {
        this.filterPopover.classList.add('hidden');
      }
    });
  }

  focusSearch(): void {
    this.searchInput.focus();
    this.searchInput.select();
  }

  get searchFocused(): boolean {
    return document.activeElement === this.searchInput;
  }

  setStatus(status: BoardStatus): void {
    this.statusDot.className = `status-dot status-${status}`;
    this.statusLabel.textContent = STATUS_LABEL[status];
  }

  setSnapshot(snapshot: GraphSnapshot): void {
    this.snapshot = snapshot;
    this.appLabel.textContent = snapshot.appId ? `${snapshot.appId} · ${snapshot.variant}` : '';
    // Drop filter selections that no longer exist, then re-render chips.
    this.renderFilterPopover();
    this.cb.onFilterChange(this.filter);
  }

  // ---- search ----

  private wireSearch(): void {
    this.searchInput.addEventListener('input', () => this.updateResults());
    this.searchInput.addEventListener('focus', () => this.updateResults());
    this.searchInput.addEventListener('blur', () => {
      setTimeout(() => this.hideResults(), 150);
    });
    this.searchInput.addEventListener('keydown', (ev) => {
      if (ev.key === 'ArrowDown') {
        ev.preventDefault();
        this.moveActive(1);
      } else if (ev.key === 'ArrowUp') {
        ev.preventDefault();
        this.moveActive(-1);
      } else if (ev.key === 'Enter') {
        ev.preventDefault();
        const pick = this.results[this.activeIndex >= 0 ? this.activeIndex : 0];
        if (pick) {
          this.cb.onSelectNode(pick.id);
          this.searchInput.blur();
          this.hideResults();
        }
      } else if (ev.key === 'Escape') {
        this.searchInput.value = '';
        this.searchInput.blur();
        this.hideResults();
      }
      ev.stopPropagation();
    });
  }

  private updateResults(): void {
    const q = this.searchInput.value.trim().toLowerCase();
    if (!this.snapshot || q.length === 0) {
      this.hideResults();
      return;
    }
    this.results = this.snapshot.nodes
      .filter(
        (n) =>
          n.displayName.toLowerCase().includes(q) ||
          n.type.toLowerCase().includes(q) ||
          (n.qualifier ?? '').toLowerCase().includes(q),
      )
      .slice(0, 12);
    this.activeIndex = this.results.length > 0 ? 0 : -1;
    this.renderResults();
  }

  private moveActive(delta: number): void {
    if (this.results.length === 0) return;
    this.activeIndex = (this.activeIndex + delta + this.results.length) % this.results.length;
    this.renderResults();
  }

  private renderResults(): void {
    clear(this.searchResults);
    if (this.results.length === 0) {
      this.searchResults.classList.remove('hidden');
      this.searchResults.append(el('div', { class: 'search-empty muted', text: 'no matches' }));
      return;
    }
    this.searchResults.classList.remove('hidden');
    this.results.forEach((node, i) => {
      const row = el(
        'div',
        {
          class: `search-row${i === this.activeIndex ? ' active' : ''}`,
          onClick: () => {
            this.cb.onSelectNode(node.id);
            this.hideResults();
          },
        },
        el('span', { class: 'search-name', text: node.displayName }),
        node.qualifier ? el('span', { class: 'chip chip-muted', text: `@${node.qualifier}` }) : null,
        el('span', { class: 'search-type mono muted', text: node.type }),
      );
      row.addEventListener('mouseenter', () => {
        this.activeIndex = i;
        this.renderResults();
      });
      this.searchResults.append(row);
    });
  }

  private hideResults(): void {
    this.searchResults.classList.add('hidden');
    this.results = [];
    this.activeIndex = -1;
  }

  // ---- filters ----

  private toggleFilters(): void {
    this.filterPopover.classList.toggle('hidden');
    if (!this.filterPopover.classList.contains('hidden')) this.renderFilterPopover();
  }

  private renderFilterPopover(): void {
    clear(this.filterPopover);
    if (!this.snapshot) return;
    const snap = this.snapshot;

    const scopes = [...new Set(snap.nodes.map((n) => n.scope ?? null))].sort((a, b) => (a ?? '').localeCompare(b ?? ''));
    const modules = [...new Set(snap.nodes.map((n) => n.providedBy?.gradleModule).filter((m): m is string => !!m))].sort();
    const kinds = ALL_KINDS.filter((k) => snap.nodes.some((n) => n.kind === k));

    const group = <T,>(
      title: string,
      values: T[],
      selected: Set<T>,
      label: (v: T) => string,
      color: (v: T) => string,
    ): HTMLElement => {
      const wrap = el('div', { class: 'filter-group' }, el('h4', { text: title }));
      const row = el('div', { class: 'filter-chips' });
      for (const v of values) {
        const chip = el('button', {
          class: `chip chip-toggle${selected.has(v) ? ' on' : ''}`,
          text: label(v),
          onClick: () => {
            if (selected.has(v)) selected.delete(v);
            else selected.add(v);
            this.renderFilterPopover();
            this.cb.onFilterChange(this.filter);
          },
        });
        const c = color(v);
        chip.style.color = c;
        chip.style.borderColor = withAlpha(c, selected.has(v) ? 0.9 : 0.4);
        if (selected.has(v)) chip.style.background = withAlpha(c, 0.16);
        row.append(chip);
      }
      wrap.append(row);
      return wrap;
    };

    this.filterPopover.append(
      group('Scope', scopes, this.filter.scopes, (s) => s ?? 'unscoped', (s) => scopeColor(s)),
      group('Kind', kinds, this.filter.kinds, (k) => k, () => '#9aa4bd'),
      group('Module', modules, this.filter.modules, (m) => m, (m) => moduleColor(m)),
      el(
        'div',
        { class: 'filter-footer' },
        el('button', {
          class: 'tb-btn',
          text: 'Clear filters',
          onClick: () => {
            this.filter = emptyFilter();
            this.renderFilterPopover();
            this.cb.onFilterChange(this.filter);
          },
        }),
      ),
    );
    this.filterBtn.classList.toggle('filters-on', filterActive(this.filter));
  }
}
