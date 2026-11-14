/**
 * The board's chrome, laid out like an analytics app:
 *  - navbar: the app, class search ('/' focuses; Enter flies to the node),
 *    Graph / Decisions / Runtime, every module with its colour and class count,
 *    and Open / Export / Legend at the foot
 *  - top bar: breadcrumb and graph size, the module-box switch, Arrange, and a
 *    menu with Fit and Zoom to selection
 *  - filter bar: Lifetime / Kind / Module dropdowns that show their value
 */

import type { GraphNode, GraphSnapshot, NodeKind } from '../model/graph';
import kiteMark from '../assets/kite.png';
import { kindLabel } from '../canvas/NodeRenderer';
import { moduleColor, scopeColor, type ThemeChoice } from '../canvas/theme';
import { clear, el } from './dom';
import { iconSvg, type IconName } from './icons';

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
  onToggleContainers: () => void;
  onShowGraph: () => void;
  onShowDecisions: () => void;
  onShowRuntime: () => void;
  onSelectModule: (lane: string) => void;
  onThemeChange: (choice: ThemeChoice) => void;
}

const THEME_LABELS: Record<ThemeChoice, string> = { system: 'System', light: 'Light', dark: 'Dark' };

/** One row of the navbar's module list. */
export interface ModuleEntry {
  lane: string;
  count: number;
}

const ALL_KINDS: NodeKind[] = ['injectable', 'provides', 'boundInterface', 'entryPoint', 'set', 'map', 'supplied', 'external'];

function kindName(kind: NodeKind): string {
  return kind === 'external' ? 'external' : kindLabel(kind, false);
}

function icon(name: IconName): HTMLElement {
  return el('span', { class: 'icon', html: iconSvg(name) });
}

export class Toolbar {
  readonly nav: HTMLElement;
  readonly topbar: HTMLElement;
  readonly filterbar: HTMLElement;

  private readonly searchInput: HTMLInputElement;
  private readonly searchResults: HTMLElement;
  private readonly projectSub: HTMLElement;
  private readonly crumbApp: HTMLElement;
  private readonly variantTag: HTMLElement;
  private readonly meta: HTMLElement;
  private readonly containersSwitch: HTMLButtonElement;
  private readonly decisionsEnd: HTMLElement;
  private readonly runtimeItem: HTMLElement;
  private readonly runtimeEnd: HTMLElement;
  private readonly modulesLabel: HTMLElement;
  private readonly modulesList: HTMLElement;
  private readonly themeEnd: HTMLElement;
  private themeChoice: ThemeChoice = 'system';
  private readonly filterButtons: { scope: HTMLButtonElement; kind: HTMLButtonElement; module: HTMLButtonElement };
  private readonly clearBtn: HTMLButtonElement;
  private readonly menu: HTMLElement;

  private snapshot: GraphSnapshot | null = null;
  private filter: FilterState = emptyFilter();
  private results: GraphNode[] = [];
  private activeIndex = -1;
  private menuFor: HTMLElement | null = null;
  private renderMenu: (() => void) | null = null;

  constructor(private readonly cb: ToolbarCallbacks) {
    // ---- navbar ----
    this.searchInput = el('input', { class: 'nav-search-input', type: 'text', placeholder: 'Search classes' });
    this.searchResults = el('div', { class: 'search-results hidden' });
    this.projectSub = el('span', { text: 'waiting for a build' });
    this.decisionsEnd = el('span', { class: 'end' });
    this.runtimeEnd = el('span', { class: 'end count' });
    this.modulesLabel = el('div', { class: 'nav-label hidden', text: 'Modules' });
    this.modulesList = el('div', { class: 'nav-modules' });
    this.themeEnd = el('span', { class: 'end count', text: THEME_LABELS.system });
    const themeItem = this.navItem('theme', 'Theme', () =>
      this.toggleMenu(themeItem, () =>
        (['system', 'light', 'dark'] as const).map((choice) =>
          el(
            'button',
            {
              class: `menu-row${choice === this.themeChoice ? ' on' : ''}`,
              onClick: () => {
                this.closeMenu();
                cb.onThemeChange(choice);
              },
            },
            el('span', { class: 'tick', html: choice === this.themeChoice ? iconSvg('check') : '' }),
            el('span', { class: 'label', text: THEME_LABELS[choice] }),
          ),
        ),
      ),
    this.themeEnd);

    const logo = document.createElement('img');
    logo.src = kiteMark;
    logo.alt = '';

    this.runtimeItem = this.navItem('runtime', 'Runtime', () => cb.onShowRuntime(), this.runtimeEnd);
    this.nav = el(
      'nav',
      { class: 'nav' },
      el('div', { class: 'nav-project' }, logo, el('div', { class: 'who' }, el('b', { text: 'Kite' }), this.projectSub)),
      el('label', { class: 'nav-search' }, icon('search'), this.searchInput, el('kbd', { text: '/' }), this.searchResults),
      this.navItem('graph', 'Graph', () => cb.onShowGraph(), null, true),
      this.navItem('decision', 'Decisions', () => cb.onShowDecisions(), this.decisionsEnd),
      this.runtimeItem,
      this.modulesLabel,
      this.modulesList,
      el('div', { class: 'nav-spacer' }),
      el(
        'div',
        { class: 'nav-foot' },
        this.navItem('folder', 'Open graph.json', () => cb.onOpenFile()),
        this.navItem('download', 'Export PNG', () => cb.onExportPng()),
        this.navItem('legend', 'Legend', () => cb.onLegend(), el('kbd', { class: 'end', text: '?' })),
        themeItem,
      ),
    );
    this.setDecisionCount(0);
    this.setRuntime(null);

    // ---- top bar ----
    this.crumbApp = el('span', { class: 'crumb-app', text: 'Kite' });
    this.variantTag = el('span', { class: 'tag hidden' });
    this.meta = el('span', { class: 'meta' });
    this.containersSwitch = el('button', {
      class: 'switch on',
      title: 'Show module boxes (m)',
      onClick: () => cb.onToggleContainers(),
    });
    this.containersSwitch.setAttribute('role', 'switch');
    this.containersSwitch.setAttribute('aria-checked', 'true');
    this.containersSwitch.append(el('span', { class: 'switch-track' }), el('span', { text: 'Module boxes' }));
    const moreBtn: HTMLButtonElement = el('button', { class: 'btn icon', title: 'More', html: iconSvg('more') });
    moreBtn.addEventListener('click', () =>
      this.toggleMenu(moreBtn, () => [
        this.menuRow('Fit graph', () => cb.onFit(), 'Shift+1'),
        this.menuRow('Zoom to selection', () => cb.onZoomSelection(), 'Shift+2'),
      ]),
    );
    this.topbar = el(
      'header',
      { class: 'topbar empty' },
      el(
        'div',
        { class: 'crumbs' },
        this.crumbApp,
        el('span', { class: 'crumb-sep', text: '/' }),
        el('span', { class: 'crumb-page', text: 'Dependency graph' }),
        this.variantTag,
      ),
      this.meta,
      el('span', { class: 'grow' }),
      el(
        'div',
        { class: 'topbar-actions' },
        this.containersSwitch,
        el('button', {
          class: 'btn',
          text: 'Arrange',
          title: 'Re-run the layout (a) — clears pins',
          onClick: () => cb.onArrange(),
        }),
        moreBtn,
      ),
    );

    // ---- filter bar ----
    this.filterButtons = {
      scope: this.filterButton('Lifetime', () => this.scopeMenu()),
      kind: this.filterButton('Kind', () => this.kindMenu()),
      module: this.filterButton('Module', () => this.moduleMenu()),
    };
    this.clearBtn = el('button', {
      class: 'btn ghost sm hidden',
      text: 'Clear',
      onClick: () => {
        this.filter = emptyFilter();
        this.filterChanged();
      },
    });
    this.filterbar = el(
      'div',
      { class: 'filterbar empty' },
      this.filterButtons.scope,
      this.filterButtons.kind,
      this.filterButtons.module,
      this.clearBtn,
      el('span', { class: 'grow' }),
      el('span', { class: 'meta hint', html: 'Drag a module to move it · <kbd>Space</kbd> + drag to pan' }),
    );

    // ---- shared dropdown menu ----
    this.menu = el('div', { class: 'menu hidden' });
    document.body.append(this.menu);
    document.addEventListener('pointerdown', (ev) => {
      const t = ev.target as Node;
      if (this.menuFor && !this.menu.contains(t) && !this.menuFor.contains(t)) this.closeMenu();
    });
    window.addEventListener('keydown', (ev) => {
      if (ev.key === 'Escape') this.closeMenu();
    });
    window.addEventListener('resize', () => this.closeMenu());

    this.wireSearch();
    this.renderFilterButtons();
  }

  focusSearch(): void {
    this.searchInput.focus();
    this.searchInput.select();
  }

  get searchFocused(): boolean {
    return document.activeElement === this.searchInput;
  }

  /** Reflect the module-box on/off state on the switch. */
  setContainersActive(on: boolean): void {
    this.containersSwitch.classList.toggle('on', on);
    this.containersSwitch.setAttribute('aria-checked', String(on));
  }

  setSnapshot(snapshot: GraphSnapshot): void {
    this.snapshot = snapshot;
    const app = snapshot.appId || 'Dependency board';
    this.crumbApp.textContent = app;
    this.projectSub.textContent = snapshot.appId ? [snapshot.appId, snapshot.variant].filter(Boolean).join(' · ') : 'waiting for a build';
    this.variantTag.textContent = snapshot.variant;
    this.variantTag.classList.toggle('hidden', !snapshot.appId || !snapshot.variant);
    const classes = snapshot.nodes.length;
    this.meta.textContent = classes > 0 ? `${classes} classes · ${snapshot.edges.length} wires` : '';
    // nothing to arrange or filter until a graph arrives
    this.topbar.classList.toggle('empty', classes === 0);
    this.filterbar.classList.toggle('empty', classes === 0);
    this.renderMenu?.();
    this.renderFilterButtons();
    this.cb.onFilterChange(this.filter);
  }

  /** The navbar's module list — lanes as the layout drew them, with class counts. */
  setModules(entries: ModuleEntry[], label: 'Modules' | 'Packages'): void {
    this.modulesLabel.textContent = label;
    this.modulesLabel.classList.toggle('hidden', entries.length === 0);
    clear(this.modulesList);
    for (const { lane, count } of entries) {
      const dot = el('span', { class: 'dot' });
      dot.style.background = moduleColor(lane);
      this.modulesList.append(
        el(
          'button',
          { class: 'nav-item', title: `Select ${lane} and zoom to it`, onClick: () => this.cb.onSelectModule(lane) },
          dot,
          el('span', { class: 'label', text: lane }),
          el('span', { class: 'end count', text: String(count) }),
        ),
      );
    }
  }

  /** Pending decisions: a red badge while the build waits on any. */
  setDecisionCount(count: number): void {
    clear(this.decisionsEnd);
    this.decisionsEnd.append(count > 0 ? el('span', { class: 'badge', text: String(count) }) : el('span', { class: 'count', text: '0' }));
  }

  /** Open scopes on the device, or null when no app is connected. */
  setRuntime(openScopes: number | null): void {
    this.runtimeEnd.textContent = openScopes === null ? 'no device' : `${openScopes} scope${openScopes === 1 ? '' : 's'}`;
  }

  setThemeChoice(choice: ThemeChoice): void {
    this.themeChoice = choice;
    this.themeEnd.textContent = THEME_LABELS[choice];
  }

  setRuntimeOpen(open: boolean): void {
    this.runtimeItem.classList.toggle('on', open);
  }

  // ---- building blocks ----

  private navItem(name: IconName, label: string, onClick: () => void, end: HTMLElement | null = null, on = false): HTMLElement {
    return el(
      'button',
      { class: `nav-item${on ? ' on' : ''}`, onClick },
      icon(name),
      el('span', { class: 'label', text: label }),
      end,
    );
  }

  private filterButton(title: string, items: () => HTMLElement[]): HTMLButtonElement {
    const btn: HTMLButtonElement = el('button', { class: 'btn sm filter-btn' });
    btn.addEventListener('click', () => this.toggleMenu(btn, items));
    btn.dataset.title = title;
    return btn;
  }

  private renderFilterButtons(): void {
    const label = <T,>(selected: Set<T>, name: (v: T) => string): string => {
      if (selected.size === 0) return 'Any';
      if (selected.size === 1) return name([...selected][0]!);
      return `${selected.size} selected`;
    };
    const set = (btn: HTMLButtonElement, value: string, active: boolean) => {
      clear(btn);
      btn.classList.toggle('on', active);
      btn.append(el('span', { text: btn.dataset.title ?? '' }), el('span', { class: 'val', text: value }), icon('chevron'));
    };
    set(this.filterButtons.scope, label(this.filter.scopes, (s) => s ?? 'unscoped'), this.filter.scopes.size > 0);
    set(this.filterButtons.kind, label(this.filter.kinds, kindName), this.filter.kinds.size > 0);
    set(this.filterButtons.module, label(this.filter.modules, (m) => m), this.filter.modules.size > 0);
    this.clearBtn.classList.toggle('hidden', !filterActive(this.filter));
  }

  private filterChanged(): void {
    this.renderFilterButtons();
    this.renderMenu?.();
    this.cb.onFilterChange(this.filter);
  }

  private checkRow<T>(selected: Set<T>, value: T, label: string, color: string | null): HTMLElement {
    const on = selected.has(value);
    const swatch = color ? el('span', { class: 'swatch' }) : null;
    if (swatch && color) swatch.style.background = color;
    return el(
      'button',
      {
        class: `menu-row${on ? ' on' : ''}`,
        onClick: () => {
          if (selected.has(value)) selected.delete(value);
          else selected.add(value);
          this.filterChanged();
        },
      },
      el('span', { class: 'check', html: on ? iconSvg('check') : '' }),
      swatch,
      el('span', { class: 'label', text: label }),
    );
  }

  private scopeMenu(): HTMLElement[] {
    const nodes = this.snapshot?.nodes ?? [];
    const scopes = [...new Set(nodes.map((n) => n.scope ?? null))].sort((a, b) => (a ?? '').localeCompare(b ?? ''));
    return scopes.map((s) => this.checkRow(this.filter.scopes, s, s ?? 'unscoped', scopeColor(s)));
  }

  private kindMenu(): HTMLElement[] {
    const nodes = this.snapshot?.nodes ?? [];
    return ALL_KINDS.filter((k) => nodes.some((n) => n.kind === k)).map((k) => this.checkRow(this.filter.kinds, k, kindName(k), null));
  }

  private moduleMenu(): HTMLElement[] {
    const nodes = this.snapshot?.nodes ?? [];
    const modules = [...new Set(nodes.map((n) => n.providedBy?.gradleModule).filter((m): m is string => !!m))].sort();
    return modules.map((m) => this.checkRow(this.filter.modules, m, m, moduleColor(m)));
  }

  private menuRow(label: string, onClick: () => void, shortcut?: string): HTMLElement {
    return el(
      'button',
      {
        class: 'menu-row',
        onClick: () => {
          this.closeMenu();
          onClick();
        },
      },
      el('span', { class: 'label', text: label }),
      shortcut ? el('kbd', { class: 'end', text: shortcut }) : null,
    );
  }

  private toggleMenu(anchor: HTMLElement, items: () => HTMLElement[]): void {
    if (this.menuFor === anchor) {
      this.closeMenu();
      return;
    }
    this.menuFor = anchor;
    this.renderMenu = () => {
      clear(this.menu);
      const rows = items();
      this.menu.append(...(rows.length > 0 ? rows : [el('div', { class: 'menu-empty', text: 'Nothing to filter yet' })]));
    };
    this.renderMenu();
    this.menu.classList.remove('hidden');
    const r = anchor.getBoundingClientRect();
    const width = Math.max(200, r.width);
    this.menu.style.minWidth = `${width}px`;
    // below the anchor, or above it when the anchor sits near the bottom (the navbar foot)
    const below = r.bottom + 6;
    this.menu.style.top = `${below + this.menu.offsetHeight > window.innerHeight - 8 ? Math.max(8, r.top - 6 - this.menu.offsetHeight) : below}px`;
    this.menu.style.left = `${Math.min(r.left, window.innerWidth - width - 8)}px`;
    if (r.left + width > window.innerWidth - 8) {
      this.menu.style.left = `${Math.max(8, r.right - this.menu.offsetWidth)}px`;
    }
  }

  private closeMenu(): void {
    this.menu.classList.add('hidden');
    this.menuFor = null;
    this.renderMenu = null;
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
    this.searchResults.classList.remove('hidden');
    if (this.results.length === 0) {
      this.searchResults.append(el('div', { class: 'menu-empty', text: 'No class matches' }));
      return;
    }
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
        node.qualifier ? el('span', { class: 'tag', text: `@${node.qualifier}` }) : null,
        el('span', { class: 'search-type mono', text: node.type }),
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
}
