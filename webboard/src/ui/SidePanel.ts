/**
 * Side panel: full details of the selected node —
 * title + scope chip, "bound to", PROVIDED FROM (declaration, module,
 * file:line click-to-copy), INJECTED INTO (each consumer edge; hover
 * highlights the edge on canvas, click flies to the consumer), DEPENDS ON,
 * and RUNTIME (live instance count + creation time). Also renders the open
 * scope tree as an overview when nothing is selected (live mode).
 */

import type { GraphNode, GraphSnapshot, OpenScope, RuntimeState } from '../model/graph';
import { scopeColor, withAlpha } from '../canvas/theme';
import { clear, copyText, el } from './dom';

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

function scopeChip(scope: string | null | undefined): HTMLElement {
  const color = scopeColor(scope ?? null);
  const chip = el('span', { class: 'chip', text: scope ?? 'unscoped' });
  chip.style.color = color;
  chip.style.borderColor = withAlpha(color, 0.55);
  chip.style.background = withAlpha(color, 0.12);
  return chip;
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

  /** Node detail view. `changedFields` briefly highlights refreshed fields. */
  show(node: GraphNode, snapshot: GraphSnapshot, runtime: RuntimeState | null, changedFields?: Set<string>): void {
    this.currentNodeId = node.id;
    this.root.classList.remove('hidden');
    clear(this.root);

    const changed = (field: string) => (changedFields?.has(field) ? ' field-changed' : '');

    // ---- header ----
    const header = el(
      'div',
      { class: 'panel-header' },
      el(
        'div',
        { class: 'panel-title-row' },
        el('h2', { class: 'panel-title', text: node.displayName, title: node.type }),
        el('span', { class: `panel-scope${changed('scope')}` }, scopeChip(node.scope)),
        el('button', { class: 'icon-btn panel-close', text: '✕', title: 'Close (Esc)', onClick: () => this.cb.onClose() }),
      ),
      el('div', { class: 'panel-subtitle mono', text: node.qualifier ? `@${node.qualifier} ${node.type}` : node.type }),
    );
    if (node.boundTo.length > 0) {
      header.append(
        el(
          'div',
          { class: `panel-boundto${changed('boundTo')}` },
          el('span', { class: 'muted', text: 'bound to: ' }),
          ...node.boundTo.map((t, i) =>
            el('span', {}, el('span', { class: 'link mono', text: simpleName(t), title: t, onClick: () => this.cb.onFlyToNode(t) }), i < node.boundTo.length - 1 ? el('span', { text: ', ' }) : null),
          ),
        ),
      );
    }
    this.root.append(header);

    const body = el('div', { class: 'panel-body' });
    this.root.append(body);

    // ---- PROVIDED FROM ----
    const provided = el('section', { class: `panel-section${changed('providedBy')}` }, el('h3', { text: 'Provided from' }));
    if (node.providedBy) {
      const p = node.providedBy;
      const loc = `${p.file}:${p.line}`;
      provided.append(
        el(
          'div',
          { class: 'entry' },
          el('div', { class: 'entry-main' }, el('span', { class: 'entry-name', text: p.declaration }), el('span', { class: 'chip chip-muted', text: p.gradleModule })),
          el('div', {
            class: 'entry-loc mono copyable',
            text: loc,
            title: 'Click to copy path',
            onClick: () => {
              void copyText(loc).then((ok) => ok && this.cb.onCopied(loc));
            },
          }),
        ),
      );
    } else {
      provided.append(el('div', { class: 'muted', text: node.kind === 'external' ? 'external binding (provided by the host)' : '—' }));
    }
    body.append(provided);

    // ---- INJECTED INTO ----
    const consumers = snapshot.edges.filter((e) => e.to === node.id);
    const injected = el('section', { class: 'panel-section' }, el('h3', { text: `Injected into (${consumers.length})` }));
    if (consumers.length === 0) {
      injected.append(el('div', { class: 'muted', text: 'no consumers — orphan?' }));
    }
    for (const edge of consumers) {
      const consumer = snapshot.nodes.find((n) => n.id === edge.from);
      const site = edge.site ? `${edge.site.file}:${edge.site.line}` : null;
      const deferredTag = edge.deferred !== 'none' ? edge.deferred === 'lazy' ? 'Lazy' : 'Provider' : null;
      const row = el(
        'div',
        {
          class: 'entry hoverable',
          onMouseenter: () => this.cb.onHoverEdge(edge.id),
          onMouseleave: () => this.cb.onHoverEdge(null),
          onClick: () => this.cb.onFlyToNode(edge.from),
        },
        el(
          'div',
          { class: 'entry-main' },
          el('span', { class: 'entry-name link', text: consumer?.displayName ?? simpleName(edge.from), title: edge.from }),
          el('span', {
            class: 'entry-site muted',
            text: [edge.siteKind === 'constructorParam' ? 'constructor' : edge.siteKind === 'field' ? 'field ⚡' : 'provides', deferredTag, edge.paramName ? `param ${edge.paramName}` : null]
              .filter(Boolean)
              .join(' · '),
          }),
        ),
        site
          ? el('div', {
              class: 'entry-loc mono copyable',
              text: site,
              title: 'Click to copy path',
              onClick: (ev) => {
                ev.stopPropagation();
                void copyText(site).then((ok) => ok && this.cb.onCopied(site));
              },
            })
          : null,
      );
      injected.append(row);
    }
    body.append(injected);

    // ---- DEPENDS ON ----
    const deps = snapshot.edges.filter((e) => e.from === node.id);
    const dependsOn = el('section', { class: 'panel-section' }, el('h3', { text: `Depends on (${deps.length})` }));
    if (deps.length === 0) dependsOn.append(el('div', { class: 'muted', text: 'no dependencies' }));
    for (const edge of deps) {
      const dep = snapshot.nodes.find((n) => n.id === edge.to);
      dependsOn.append(
        el(
          'div',
          {
            class: 'entry hoverable',
            onMouseenter: () => this.cb.onHoverEdge(edge.id),
            onMouseleave: () => this.cb.onHoverEdge(null),
            onClick: () => this.cb.onFlyToNode(edge.to),
          },
          el(
            'div',
            { class: 'entry-main' },
            el('span', { class: 'entry-name link', text: dep?.displayName ?? simpleName(edge.to), title: edge.to }),
            el('span', {
              class: 'entry-site muted',
              text: [edge.paramName ?? '', edge.deferred !== 'none' ? `(${edge.deferred})` : ''].filter(Boolean).join(' '),
            }),
          ),
        ),
      );
    }
    body.append(dependsOn);

    // ---- RUNTIME ----
    if (runtime) {
      const info = runtimeInfoFor(node.id, runtime);
      const section = el('section', { class: 'panel-section' }, el('h3', { text: 'Runtime' }));
      if (info.count > 0) {
        const scopeName = runtime.openScopes.find((s) => s.id === info.scopeId)?.name ?? info.scopeId ?? '?';
        section.append(
          el(
            'div',
            { class: 'entry' },
            el('div', { class: 'entry-main' }, el('span', { class: 'entry-name', text: `${info.count} instance${info.count > 1 ? 's' : ''}` }), el('span', { class: 'chip chip-green', text: `${scopeName} scope` })),
            info.createdAt !== null
              ? el('div', {
                  class: 'entry-loc muted',
                  text: `created ${info.creationMicros !== null ? fmtMicros(info.creationMicros) : ''} · ${fmtTime(info.createdAt)}`,
                })
              : null,
          ),
        );
      } else {
        section.append(el('div', { class: 'muted', text: 'not instantiated yet' }));
      }
      body.append(section);
    }
  }

  /** Overview mode (live, nothing selected): open scope tree + graph stats. */
  showOverview(snapshot: GraphSnapshot, runtime: RuntimeState | null): void {
    this.currentNodeId = null;
    this.root.classList.remove('hidden');
    clear(this.root);

    this.root.append(
      el(
        'div',
        { class: 'panel-header' },
        el(
          'div',
          { class: 'panel-title-row' },
          el('h2', { class: 'panel-title', text: snapshot.appId || 'dependency graph' }),
          el('button', { class: 'icon-btn panel-close', text: '✕', title: 'Close', onClick: () => this.cb.onClose() }),
        ),
        el('div', { class: 'panel-subtitle', text: `${snapshot.variant} · ${snapshot.nodes.length} nodes · ${snapshot.edges.length} edges` }),
      ),
    );

    const body = el('div', { class: 'panel-body' });
    this.root.append(body);

    if (runtime) {
      const section = el('section', { class: 'panel-section' }, el('h3', { text: 'Open scopes' }));
      const byParent = new Map<string | null, OpenScope[]>();
      for (const s of runtime.openScopes) {
        const key = s.parent ?? null;
        const arr = byParent.get(key) ?? [];
        arr.push(s);
        byParent.set(key, arr);
      }
      const renderLevel = (parent: string | null, depth: number): void => {
        for (const s of byParent.get(parent) ?? []) {
          const count = runtime.instances.filter((i) => i.scopeId === s.id).length;
          const row = el(
            'div',
            { class: 'scope-row' },
            el('span', {}, scopeChip(s.name)),
            el('span', { class: 'mono scope-id', text: s.id }),
            el('span', { class: 'muted', text: `${count} inst` }),
          );
          row.style.paddingLeft = `${depth * 16}px`;
          section.append(row);
          renderLevel(s.id, depth + 1);
        }
      };
      renderLevel(null, 0);
      if (runtime.openScopes.length === 0) section.append(el('div', { class: 'muted', text: 'no open scopes' }));
      body.append(section);
    }

    const hint = el('section', { class: 'panel-section' }, el('h3', { text: 'Tips' }));
    hint.append(
      el('div', { class: 'muted tip', text: 'Click a node to inspect where it is provided from and injected into.' }),
      el('div', { class: 'muted tip', html: 'Search <kbd>/</kbd> · Fit <kbd>Shift+1</kbd> · Zoom to selection <kbd>Shift+2</kbd>' }),
      el('div', { class: 'muted tip', text: 'Drag a node to pin it — pins survive rebuilds.' }),
    );
    body.append(hint);
  }
}

export function runtimeInfoFor(nodeId: string, runtime: RuntimeState): RuntimeNodeInfo {
  const instances = runtime.instances.filter((i) => i.nodeId === nodeId);
  if (instances.length === 0) return { count: 0, scopeId: null, createdAt: null, creationMicros: null };
  const latest = instances.reduce((a, b) => (b.createdAt > a.createdAt ? b : a));
  return { count: instances.length, scopeId: latest.scopeId, createdAt: latest.createdAt, creationMicros: latest.creationMicros };
}
