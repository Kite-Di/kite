/**
 * Details panel, docked to the right: the selected node's kind tile, name, type
 * (click to copy) and tags; PROVIDED FROM as a property table (module, source
 * file:line click-to-copy, why it is in the graph); INJECTED INTO and DEPENDS ON
 * as small tables (hover highlights the edge on canvas, click flies to the other
 * end); RUNTIME (live instance count + creation time). With nothing selected it
 * shows the device's open scopes.
 */

import type { GraphNode, GraphSnapshot, OpenScope, RuntimeState } from '../model/graph';
import { kindLabel } from '../canvas/NodeRenderer';
import { moduleColor, scopeColor } from '../canvas/theme';
import { clear, copyText, el, type Child } from './dom';
import { iconSvg, kindSvg } from './icons';

const SITE_LABELS: Record<string, string> = {
  constructorParam: 'constructor',
  field: 'field ⚡',
  providesParam: 'provides',
  setContribution: 'set element',
  mapContribution: 'map entry',
  binds: 'implements',
  viewModel: 'ViewModel adapter',
};

/** Why the node is wired (schema v2 `inferredBy`). */
const INFERRED_LABELS: Record<string, string> = {
  implementation: 'Implements a project interface (R1)',
  'root-rule': '@Root decision in GraphRules.kt (R3)',
  closure: 'Constructor dependency of an included binding (R4)',
};

function siteLabel(kind: string): string {
  return SITE_LABELS[kind] ?? kind;
}

export interface SidePanelCallbacks {
  onClose: () => void;
  onHoverEdge: (edgeId: string | null) => void;
  onFlyToNode: (nodeId: string) => void;
  onCopied: (what: string) => void;
}

export interface RuntimeNodeInfo {
  count: number;
  scopeId: string | null;
  createdAt: number | null;
  creationMicros: number | null;
}

function tag(text: string, color: string | null = null, extraClass = ''): HTMLElement {
  const t = el('span', { class: `tag${extraClass}`, text });
  if (color) t.style.setProperty('--c', color);
  return t;
}

function fmtTime(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function fmtMicros(us: number): string {
  if (us < 1000) return `${us} µs`;
  if (us < 1_000_000) return `${(us / 1000).toFixed(1)} ms`;
  return `${(us / 1_000_000).toFixed(2)} s`;
}

function simpleName(fqn: string): string {
  const at = fqn.indexOf('@');
  const t = at >= 0 ? fqn.slice(at + 1) : fqn;
  return t.split('.').pop() ?? fqn;
}

/** The last two path segments — enough to recognise the file, short enough to fit. */
function shortPath(file: string, line: number): string {
  return `${file.split('/').slice(-2).join('/')}:${line}`;
}

function section(title: string, count: number | null, ...children: Child[]): HTMLElement {
  return el(
    'section',
    { class: 'p-sec' },
    el('h3', {}, el('span', { text: title }), count === null ? null : el('span', { class: 'count', text: String(count) })),
    ...children,
  );
}

function props(rows: [string, Child][]): HTMLElement {
  return el('dl', { class: 'props' }, ...rows.map(([k, v]) => el('div', {}, el('dt', { text: k }), el('dd', {}, v))));
}

function table(head: [string, string], rows: HTMLElement[]): HTMLElement {
  return el('div', { class: 'tbl' }, el('div', { class: 'th' }, el('span', { text: head[0] }), el('span', { text: head[1] })), ...rows);
}

export class SidePanel {
  readonly root: HTMLElement;
  private currentNodeId: string | null = null;

  constructor(
    parent: HTMLElement,
    private readonly cb: SidePanelCallbacks,
  ) {
    this.root = el('aside', { class: 'side-panel hidden' });
    parent.append(this.root);
  }

  get visible(): boolean {
    return !this.root.classList.contains('hidden');
  }

  get shownNodeId(): string | null {
    return this.currentNodeId;
  }

  hide(): void {
    this.root.classList.add('hidden');
    this.currentNodeId = null;
    this.cb.onHoverEdge(null);
  }

  private copyable(text: string, shown = text): HTMLElement {
    return el('span', {
      class: 'mono copyable',
      text: shown,
      title: `${text}\nClick to copy`,
      onClick: (ev) => {
        ev.stopPropagation();
        void copyText(text).then((ok) => ok && this.cb.onCopied(text));
      },
    });
  }

  private closeButton(): HTMLElement {
    return el('button', { class: 'btn ghost icon panel-close', title: 'Close (Esc)', html: iconSvg('close'), onClick: () => this.cb.onClose() });
  }

  /** Node detail view. `changedFields` briefly highlights refreshed fields. */
  show(node: GraphNode, snapshot: GraphSnapshot, runtime: RuntimeState | null, changedFields?: Set<string>): void {
    this.currentNodeId = node.id;
    this.root.classList.remove('hidden');
    clear(this.root);

    const changed = (field: string) => (changedFields?.has(field) ? ' field-changed' : '');
    const module = node.providedBy?.gradleModule ?? null;

    // ---- header ----
    const tile = el('span', { class: 'tile', html: kindSvg(node.kind) });
    tile.style.setProperty('--m', moduleColor(module));
    const tags = el(
      'div',
      { class: 'p-tags' },
      node.kind === 'external' ? null : tag(node.scope ?? 'unscoped', scopeColor(node.scope ?? null), changed('scope')),
      tag(kindLabel(node.kind, !!node.qualifier), null, changed('kind')),
      node.qualifier ? tag(`@${node.qualifier}`) : null,
    );
    if (node.boundTo.length > 0) {
      tags.append(
        el(
          'span',
          { class: `bound${changed('boundTo')}` },
          el('span', { text: 'bound to ' }),
          ...node.boundTo.map((t, i) =>
            el('span', {}, el('span', { class: 'link', text: simpleName(t), title: t, onClick: () => this.cb.onFlyToNode(t) }), i < node.boundTo.length - 1 ? ', ' : null),
          ),
        ),
      );
    }
    this.root.append(
      el(
        'div',
        { class: 'p-head' },
        el('div', { class: 'p-row' }, tile, el('h2', { class: 'p-title', text: node.displayName, title: node.type }), this.closeButton()),
        el(
          'div',
          { class: 'fqn' },
          this.copyable(node.type),
          el('button', {
            class: 'btn ghost icon xs',
            title: 'Copy the type',
            html: iconSvg('copy'),
            onClick: () => void copyText(node.type).then((ok) => ok && this.cb.onCopied(node.type)),
          }),
        ),
        tags,
      ),
    );

    const body = el('div', { class: 'p-body' });
    this.root.append(body);

    // ---- PROVIDED FROM ----
    const rows: [string, Child][] = [];
    if (node.providedBy) {
      const p = node.providedBy;
      rows.push(['Module', tag(p.gradleModule, moduleColor(p.gradleModule))]);
      rows.push(['Source', this.copyable(`${p.file}:${p.line}`, shortPath(p.file, p.line))]);
      if (p.declaration !== node.displayName) rows.push(['Declared as', el('span', { text: p.declaration })]);
    } else {
      rows.push(['Source', el('span', { class: 'quiet', text: node.kind === 'external' ? (node.qualifier ? 'a Graph.start argument' : 'provided by the host') : '—' })]);
    }
    if (node.inferredBy) rows.push(['Why', el('span', { text: INFERRED_LABELS[node.inferredBy] ?? node.inferredBy })]);
    const provided = section('Provided from', null, props(rows));
    provided.className += changed('providedBy');
    body.append(provided);

    // ---- INJECTED INTO ----
    const consumers = snapshot.edges.filter((e) => e.to === node.id);
    body.append(
      section(
        'Injected into',
        consumers.length,
        consumers.length === 0
          ? el('div', { class: 'quiet', text: 'Nothing injects it — an orphan?' })
          : table(
              ['Class', 'Via'],
              consumers.map((edge) => {
                const consumer = snapshot.nodes.find((n) => n.id === edge.from);
                const deferredTag = edge.deferred !== 'none' ? (edge.deferred === 'lazy' ? 'Lazy' : 'Provider') : null;
                const via = [
                  siteLabel(edge.siteKind),
                  deferredTag,
                  edge.paramName ? (edge.siteKind === 'mapContribution' ? `key ${edge.paramName}` : edge.paramName) : null,
                ]
                  .filter(Boolean)
                  .join(' · ');
                return el(
                  'div',
                  {
                    class: 'tr',
                    onMouseenter: () => this.cb.onHoverEdge(edge.id),
                    onMouseleave: () => this.cb.onHoverEdge(null),
                    onClick: () => this.cb.onFlyToNode(edge.from),
                  },
                  el(
                    'span',
                    { class: 'cell' },
                    el('span', { class: 'link', text: consumer?.displayName ?? simpleName(edge.from), title: edge.from }),
                    edge.site ? this.copyable(`${edge.site.file}:${edge.site.line}`, shortPath(edge.site.file, edge.site.line)) : null,
                  ),
                  el('span', { class: 'mono', text: via }),
                );
              }),
            ),
      ),
    );

    // ---- DEPENDS ON ----
    const deps = snapshot.edges.filter((e) => e.from === node.id);
    body.append(
      section(
        'Depends on',
        deps.length,
        deps.length === 0
          ? el('div', { class: 'quiet', text: 'No dependencies' })
          : table(
              ['Class', 'Parameter'],
              deps.map((edge) => {
                const dep = snapshot.nodes.find((n) => n.id === edge.to);
                return el(
                  'div',
                  {
                    class: 'tr',
                    onMouseenter: () => this.cb.onHoverEdge(edge.id),
                    onMouseleave: () => this.cb.onHoverEdge(null),
                    onClick: () => this.cb.onFlyToNode(edge.to),
                  },
                  el('span', { class: 'cell' }, el('span', { class: 'link', text: dep?.displayName ?? simpleName(edge.to), title: edge.to })),
                  el('span', { class: 'mono', text: [edge.paramName ?? '', edge.deferred !== 'none' ? `(${edge.deferred})` : ''].filter(Boolean).join(' ') || '—' }),
                );
              }),
            ),
      ),
    );

    // ---- RUNTIME ----
    if (runtime) {
      const info = runtimeInfoFor(node.id, runtime);
      if (info.count > 0) {
        // Unscoped: nothing caches the object, so the count is constructions, not
        // live instances — saying "3 instances" would read as a leak.
        const scoped = !!node.scope;
        const scopeName = runtime.openScopes.find((s) => s.id === info.scopeId)?.name ?? info.scopeId ?? '?';
        const runtimeRows: [string, Child][] = [
          [scoped ? 'Alive' : 'Built', el('span', { text: scoped ? `${info.count} instance${info.count > 1 ? 's' : ''}` : `${info.count} time${info.count > 1 ? 's' : ''}` })],
          ['Held by', scoped ? tag(`${scopeName} scope`, scopeColor(node.scope ?? null)) : el('span', { class: 'quiet', text: 'nothing — unscoped, not cached' })],
        ];
        if (info.createdAt !== null) {
          runtimeRows.push(['Created', el('span', { class: 'mono', text: `${fmtTime(info.createdAt)}${info.creationMicros !== null ? ` · ${fmtMicros(info.creationMicros)}` : ''}` })]);
        }
        body.append(section('Runtime', null, props(runtimeRows)));
      } else {
        body.append(section('Runtime', null, el('div', { class: 'quiet', text: 'Not created yet' })));
      }
    }
  }

  /** Overview (Runtime in the navbar): the device's open scopes and the graph's size. */
  showOverview(snapshot: GraphSnapshot, runtime: RuntimeState | null): void {
    this.currentNodeId = null;
    this.root.classList.remove('hidden');
    clear(this.root);

    this.root.append(
      el(
        'div',
        { class: 'p-head' },
        el('div', { class: 'p-row' }, el('h2', { class: 'p-title', text: 'Runtime' }), this.closeButton()),
        el('div', { class: 'p-sub', text: `${snapshot.appId || 'dependency graph'} · ${snapshot.variant} · ${snapshot.nodes.length} classes` }),
      ),
    );

    const body = el('div', { class: 'p-body' });
    this.root.append(body);

    if (runtime) {
      const byParent = new Map<string | null, OpenScope[]>();
      for (const s of runtime.openScopes) {
        const key = s.parent ?? null;
        const arr = byParent.get(key) ?? [];
        arr.push(s);
        byParent.set(key, arr);
      }
      const rows: HTMLElement[] = [];
      const renderLevel = (parent: string | null, depth: number): void => {
        for (const s of byParent.get(parent) ?? []) {
          const count = runtime.instances.filter((i) => i.scopeId === s.id).length;
          const name = el('span', { class: 'cell' }, tag(s.name), el('span', { class: 'mono', text: s.id }));
          name.style.paddingLeft = `${depth * 14}px`;
          rows.push(el('div', { class: 'tr' }, name, el('span', { class: 'mono', text: String(count) })));
          renderLevel(s.id, depth + 1);
        }
      };
      renderLevel(null, 0);
      body.append(
        section(
          'Open scopes',
          runtime.openScopes.length,
          rows.length > 0 ? table(['Scope', 'Instances'], rows) : el('div', { class: 'quiet', text: 'No open scopes' }),
        ),
      );
    } else {
      body.append(
        section(
          'Open scopes',
          null,
          el('div', { class: 'quiet', text: 'No app connected. Add the inspector and run the app on a device to see what exists right now.' }),
        ),
      );
    }

    body.append(
      section(
        'Tips',
        null,
        el('div', { class: 'quiet tip', text: 'Click a class to see where it is provided from and what it is injected into.' }),
        el('div', { class: 'quiet tip', html: 'Search <kbd>/</kbd> · Fit <kbd>Shift+1</kbd> · Zoom to selection <kbd>Shift+2</kbd>' }),
        el('div', { class: 'quiet tip', text: 'Drag a class to pin it — pins survive rebuilds.' }),
      ),
    );
  }
}

export function runtimeInfoFor(nodeId: string, runtime: RuntimeState): RuntimeNodeInfo {
  const instances = runtime.instances.filter((i) => i.nodeId === nodeId);
  if (instances.length === 0) return { count: 0, scopeId: null, createdAt: null, creationMicros: null };
  const latest = instances.reduce((a, b) => (b.createdAt > a.createdAt ? b : a));
  return { count: instances.length, scopeId: latest.scopeId, createdAt: latest.createdAt, creationMicros: latest.creationMicros };
}
