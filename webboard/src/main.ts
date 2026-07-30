/**
 * Bootstrap + orchestration: data source detection (live vs
 * static), scene population, live-diff animation, interactions,
 * persistence and all UI wiring.
 */

import './styles.css';
import { Animator, edgeDraw, errorShake, fadeOut, glowFlash, moveTo, nodeEnter, pulse } from './canvas/animations';
import { Engine } from './canvas/Engine';
import { measureNode } from './canvas/NodeRenderer';
import { makeVEdge, makeVNode, Scene, type VNode } from './canvas/Scene';
import { detectLiveMode, LiveSource, type HelloMessage, type RuntimeEvent } from './data/LiveSource';
import { StaticSource } from './data/StaticSource';
import { persistence, type PinnedPositions } from './data/persistence';
import { placeIncremental, shouldUseIncremental, type Position } from './layout/incremental';
import { layeredLayout } from './layout/layered';
import { deriveLanes } from './model/lanes';
import { diffSnapshots, nodeChanged, summarizeOps } from './model/diff';
import {
  emptySnapshot,
  SUPPORTED_SCHEMA_VERSIONS,
  type GraphNode,
  type GraphSnapshot,
  type PatchOp,
  type RuntimeState,
} from './model/graph';
import { parseDecisions, type PendingDecision } from './model/decisions';
import { DecisionCards } from './ui/DecisionCards';
import { el } from './ui/dom';
import { Minimap } from './ui/Minimap';
import { Overlays } from './ui/Overlays';
import { SidePanel } from './ui/SidePanel';
import { emptyFilter, filterActive, nodePassesFilter, Toolbar, type BoardStatus, type FilterState } from './ui/Toolbar';
import { Legend } from './ui/Legend';
import { Toasts } from './ui/Toast';

type Mode = 'boot' | 'live' | 'static';

class App {
  private readonly scene = new Scene();
  private readonly animator = new Animator();
  private readonly engine: Engine;

  private readonly canvas: HTMLCanvasElement;
  private readonly toolbar: Toolbar;
  private readonly sidePanel: SidePanel;
  private readonly minimap: Minimap;
  private readonly toasts: Toasts;
  private readonly overlays: Overlays;
  private readonly legend: Legend;
  private readonly staticSource: StaticSource;
  private readonly decisionCards: DecisionCards;
  private decisionsTimer: ReturnType<typeof setInterval> | null = null;

  private live: LiveSource | null = null;
  private mode: Mode = 'boot';
  private snapshot: GraphSnapshot = emptySnapshot();
  private runtime: RuntimeState | null = null;
  private fingerprint: string | null = null;
  private appId: string | null = null;
  private pins: PinnedPositions = {};
  private filter: FilterState = emptyFilter();
  private hasLaidOut = false;
  private cameraRestored = false;
  private overviewDismissed = false;
  private impactMode = false;
  private staticFileLoaded = false;
  private probeDelay = 1000;
  private cameraSaveTimer: ReturnType<typeof setTimeout> | null = null;
  private layoutEpoch = 0;

  constructor(root: HTMLElement) {
    this.canvas = el('canvas', { class: 'board' });
    root.append(this.canvas);
    this.engine = new Engine(this.canvas, this.scene, this.animator);

    this.toolbar = new Toolbar(root, {
      onFit: () => this.fit(),
      onZoomSelection: () => this.zoomToSelection(),
      onSelectNode: (id) => this.selectNode(id, { fly: true }),
      onFilterChange: (f) => this.applyFilter(f),
      onOpenFile: () => this.staticSource.openPicker(),
      onExportPng: () => this.exportPng(),
      onLegend: () => this.legend.toggle(),
      onArrange: () => this.arrange(),
    });

    this.sidePanel = new SidePanel(root, {
      onClose: () => {
        this.overviewDismissed = true;
        this.deselect(false);
      },
      onHoverEdge: (edgeId) => {
        this.scene.highlightEdgeId = edgeId;
        this.engine.requestRender();
      },
      onFlyToNode: (nodeId) => this.selectNode(nodeId, { fly: true }),
      onCopied: (what) => this.toasts.show(`copied · ${what}`, { kind: 'success', ttlMs: 1800 }),
    });

    this.minimap = new Minimap(root, this.scene, this.engine.camera, () => this.engine.requestRender());
    this.toasts = new Toasts(root);
    this.decisionCards = new DecisionCards(root, {
      onApply: (decision, candidateFqn) => this.applyDecision(decision, candidateFqn),
    });
    this.legend = new Legend(root);
    this.overlays = new Overlays(
      root,
      () => this.staticSource.openPicker(),
      (what) => this.toasts.show(`copied · ${what}`, { kind: 'success', ttlMs: 1800 }),
    );

    this.staticSource = new StaticSource({
      onSnapshot: (snapshot, fileName) => {
        this.staticFileLoaded = true;
        if (this.live) {
          this.live.dispose();
          this.live = null;
        }
        this.enterMode('static');
        this.toasts.show(`loaded ${fileName}`, { kind: 'success' });
        void this.setSnapshot(snapshot, { animate: true, fingerprint: `file:${fileName}:${Date.now()}` });
      },
      onError: (message) => this.toasts.show(`could not load file — ${message}`, { kind: 'error' }),
      onDragState: (active) => this.overlays.setDropActive(active),
    });

    this.engine.onAfterRender = () => this.minimap.render();
    this.engine.camera.onChange = () => {
      this.engine.requestRender();
      this.scheduleCameraSave();
    };

    this.wireInteractions();
    this.wireKeyboard();
  }

  // ---------------------------------------------------------------- boot

  async start(): Promise<void> {
    // 1. Restore the persisted snapshot immediately: the graph
    //    shows even before (or without) a connection.
    const lastAppId = persistence.lastAppId();
    if (lastAppId) {
      const session = persistence.loadSession(lastAppId);
      if (session && session.snapshot.nodes.length > 0) {
        this.appId = lastAppId;
        this.pins = persistence.loadPins(lastAppId);
        this.fingerprint = session.buildFingerprint;
        const pose = persistence.loadCamera(lastAppId);
        if (pose) {
          this.engine.camera.x = pose.x;
          this.engine.camera.y = pose.y;
          this.engine.camera.scale = pose.scale;
          this.cameraRestored = true;
        }
        await this.setSnapshot(session.snapshot, { animate: false, fingerprint: session.buildFingerprint, persist: false });
      }
    }

    // 2. Detect live mode: does the page origin serve /api/graph?
    void this.probeLive();
  }

  private async probeLive(): Promise<void> {
    const fetched = await detectLiveMode();
    if (fetched) {
      this.enterMode('live');
      this.startLive(fetched);
      return;
    }
    if (this.mode === 'boot') {
      this.enterMode('static');
      if (this.snapshot.nodes.length === 0) this.overlays.showConnectHint();
    }
    // Keep retrying with backoff (1 s → 10 s cap) until the app appears —
    // unless the user deliberately loaded a static file.
    if (!this.staticFileLoaded) {
      setTimeout(() => {
        if (!this.staticFileLoaded && this.mode !== 'live') void this.probeLive();
      }, this.probeDelay);
      this.probeDelay = Math.min(this.probeDelay * 2, 10_000);
    }
  }

  private enterMode(mode: Mode): void {
    this.mode = mode;
    this.engine.liveMode = mode === 'live';
    this.toolbar.setStatus(mode === 'live' ? 'live' : 'static');
    if (mode === 'live') this.startDecisionsPoll();
    else this.stopDecisionsPoll();
    this.engine.requestRender();
  }

  // ------------------------------------------------- decision cards

  /**
   * decisions.json is a tiny host-side file; a 2 s poll is simpler and more
   * robust than threading it through the graph socket — cards must show up
   * precisely when the build FAILS and no new snapshot arrives.
   */
  private startDecisionsPoll(): void {
    if (this.decisionsTimer !== null) return;
    const tick = async (): Promise<void> => {
      try {
        const res = await fetch('/api/decisions', { cache: 'no-store' });
        if (res.ok) this.decisionCards.setDecisions(parseDecisions(await res.json()));
      } catch {
        // server gone — the connection banner already tells the story
      }
    };
    void tick();
    this.decisionsTimer = setInterval(() => void tick(), 2000);
  }

  private stopDecisionsPoll(): void {
    if (this.decisionsTimer !== null) {
      clearInterval(this.decisionsTimer);
      this.decisionsTimer = null;
    }
    this.decisionCards.setDecisions({ module: '', pending: [] });
  }

  private async applyDecision(decision: PendingDecision, candidateFqn: string): Promise<{ file: string; line: number }> {
    const res = await fetch('/api/decisions/apply', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: decision.id, candidateFqn }),
    });
    const body = (await res.json().catch(() => ({}))) as { ok?: boolean; file?: string; line?: number; error?: string };
    if (!res.ok || body.ok !== true || typeof body.file !== 'string' || typeof body.line !== 'number') {
      this.toasts.show(`could not write the rule — ${body.error ?? res.statusText}`, { kind: 'error', ttlMs: 6000 });
      throw new Error(body.error ?? 'apply failed');
    }
    this.toasts.show(`wrote ${body.file}:${body.line} — rebuild to apply`, { kind: 'success', ttlMs: 6000 });
    return { file: body.file, line: body.line };
  }

  private startLive(initial: GraphSnapshot): void {
    // Render the fetched snapshot right away if we have nothing better.
    if (this.snapshot.nodes.length === 0) {
      void this.setSnapshot(initial, { animate: false, fingerprint: null });
    }

    this.live = new LiveSource(LiveSource.defaultUrl(), {
      onHello: (hello) => this.onHello(hello),
      onSnapshot: (snapshot, hello) => void this.onLiveSnapshot(snapshot, hello),
      onRuntimeEvent: (event) => this.onRuntimeEvent(event),
      onStatus: (status) => this.onLiveStatus(status),
      onEventsDropped: () => this.toasts.show('events dropped — resynced', { kind: 'warn' }),
      onError: (message) => this.toasts.show(message, { kind: 'error' }),
    });
    this.live.connect();
  }

  private onHello(hello: HelloMessage): void {
    if (!SUPPORTED_SCHEMA_VERSIONS.includes(hello.schemaVersion)) {
      this.overlays.showSchemaError(hello.schemaVersion);
      this.live?.dispose();
      this.live = null;
      return;
    }
    this.appId = hello.appId;
  }

  private async onLiveSnapshot(snapshot: GraphSnapshot, hello: HelloMessage | null): Promise<void> {
    const newFingerprint = hello?.buildFingerprint ?? null;
    const sameBuild = newFingerprint !== null && newFingerprint === this.fingerprint;
    this.toasts.banner(null);

    if (this.snapshot.nodes.length === 0 || sameBuild) {
      // First data or same-fingerprint reconnect → silent resume, runtime
      // state refreshed from the snapshot.
      await this.setSnapshot(snapshot, { animate: false, fingerprint: newFingerprint });
    } else {
      // New buildFingerprint → animated diff.
      await this.setSnapshot(snapshot, { animate: true, fingerprint: newFingerprint });
    }
  }

  private onLiveStatus(status: 'connecting' | 'live' | 'disconnected'): void {
    const map: Record<string, BoardStatus> = { connecting: 'connecting', live: 'live', disconnected: 'disconnected' };
    this.toolbar.setStatus(map[status] ?? 'disconnected');
    if (status === 'disconnected') {
      this.toasts.banner('app disconnected — showing last known graph');
    } else if (status === 'live') {
      this.toasts.banner(null);
    }
  }

  // ------------------------------------------------------- snapshot flow

  private async setSnapshot(
    next: GraphSnapshot,
    opts: { animate: boolean; fingerprint: string | null; persist?: boolean },
  ): Promise<void> {
    const prev = this.snapshot;
    this.fingerprint = opts.fingerprint ?? this.fingerprint;
    if (next.appId) {
      if (this.appId !== next.appId) {
        this.pins = persistence.loadPins(next.appId);
      }
      this.appId = next.appId;
    }

    if (opts.animate && prev.nodes.length > 0) {
      await this.applyDiffAnimated(prev, next);
    } else {
      await this.rebuildScene(next);
    }

    this.snapshot = next;
    this.runtime = next.runtime ?? (this.mode === 'live' ? { openScopes: [], instances: [] } : null);
    this.applyRuntimeToScene();
    this.toolbar.setSnapshot(next);
    this.applyFilter(this.filter);
    this.refreshPanel();

    if (next.nodes.length === 0 && this.mode !== 'boot') {
      this.overlays.showEmptyGraph();
    } else if (next.nodes.length > 0) {
      this.overlays.hide();
    }

    if (opts.persist !== false && this.appId) {
      persistence.saveSession(this.appId, next, this.fingerprint);
    }
    this.engine.requestRender();
  }

  /** Full scene rebuild (initial load / silent replace). */
  private async rebuildScene(next: GraphSnapshot): Promise<void> {
    const epoch = ++this.layoutEpoch;
    this.scene.clear();

    for (const node of next.nodes) {
      const { w, h } = measureNode(node.displayName);
      const vn = makeVNode(node, w, h);
      const pin = this.pins[node.id];
      if (pin) {
        vn.x = pin.x;
        vn.y = pin.y;
        vn.pinned = true;
      }
      this.scene.addNode(vn);
    }
    for (const edge of next.edges) {
      this.scene.addEdge(makeVEdge(edge));
    }
    this.scene.markDirty();

    if (next.nodes.length === 0) return;

    const positions = await this.runLayout(next);
    if (epoch !== this.layoutEpoch) return; // superseded by a newer snapshot
    const animateMove = this.hasLaidOut;
    for (const [id, pos] of positions) {
      const vn = this.scene.nodes.get(id);
      if (!vn || vn.pinned) continue;
      if (animateMove) {
        moveTo(this.animator, vn, pos, () => this.scene.markDirty());
      } else {
        vn.x = pos.x;
        vn.y = pos.y;
      }
    }
    this.scene.markDirty();
    this.hasLaidOut = true;
    if (!this.cameraRestored) {
      this.fit();
      this.cameraRestored = true;
    }
    this.engine.requestRender();
  }

  /** Live-diff animation. */
  private async applyDiffAnimated(prev: GraphSnapshot, next: GraphSnapshot): Promise<void> {
    const epoch = ++this.layoutEpoch;
    const ops = diffSnapshots(prev, next);
    if (ops.length === 0) return; // same graph — nothing to animate

    const summary = summarizeOps(ops);
    const parts: string[] = [];
    if (summary.addedNodes) parts.push(`+${summary.addedNodes} node${summary.addedNodes > 1 ? 's' : ''}`);
    if (summary.removedNodes) parts.push(`−${summary.removedNodes} node${summary.removedNodes > 1 ? 's' : ''}`);
    if (summary.addedEdges) parts.push(`+${summary.addedEdges} edge${summary.addedEdges > 1 ? 's' : ''}`);
    if (summary.removedEdges) parts.push(`−${summary.removedEdges} edge${summary.removedEdges > 1 ? 's' : ''}`);
    if (summary.updatedNodes) parts.push(`${summary.updatedNodes} changed`);
    const buildTag = this.fingerprint ? `Build #${this.fingerprint.slice(0, 7)} · ` : '';
    this.toasts.show(`${buildTag}${parts.join(' · ')}`, {
      kind: 'info',
      ttlMs: 8000,
      onClick: () => {
        const b = this.scene.boundsOf(summary.touchedNodeIds);
        if (b) this.engine.camera.flyToBounds(b, 140);
        this.engine.requestRender();
      },
    });

    // 1. fade out removed edges/nodes, then drop them.
    const fades: Promise<void>[] = [];
    for (const op of ops) {
      if (op.op === 'removeEdge') {
        const ve = this.scene.edges.get(op.edgeId);
        if (ve) {
          ve.removing = true;
          fades.push(
            new Promise((res) =>
              fadeOut(this.animator, ve, () => this.scene.markDirty(), () => {
                this.scene.removeEdge(op.edgeId);
                res();
              }),
            ),
          );
        }
      } else if (op.op === 'removeNode') {
        const vn = this.scene.nodes.get(op.nodeId);
        if (vn) {
          vn.removing = true;
          fades.push(
            new Promise((res) =>
              fadeOut(this.animator, vn, () => this.scene.markDirty(), () => {
                this.scene.removeNode(op.nodeId);
                res();
              }),
            ),
          );
        }
      }
    }
    this.engine.requestRender();
    await Promise.all(fades);
    if (epoch !== this.layoutEpoch) return;

    // 2. layout strategy: ≤15 % changed → local barycenter
    //    seeding, untouched nodes stay; larger → full relayout w/ 400 ms tween.
    const changedNodes = summary.addedNodes + summary.removedNodes + summary.updatedNodes;
    const incremental = shouldUseIncremental(changedNodes, Math.max(next.nodes.length, 1));

    const addedNodes = ops.filter((o): o is Extract<PatchOp, { op: 'addNode' }> => o.op === 'addNode');
    const addedEdges = ops.filter((o): o is Extract<PatchOp, { op: 'addEdge' }> => o.op === 'addEdge');
    const updatedNodes = ops.filter((o): o is Extract<PatchOp, { op: 'updateNode' }> => o.op === 'updateNode');

    const newBoxes = addedNodes.map((o) => {
      const { w, h } = measureNode(o.node.displayName);
      return { id: o.node.id, w, h };
    });

    let newPositions: Map<string, Position>;
    if (incremental) {
      const existing = new Map(
        [...this.scene.nodes.values()].map((vn) => [vn.node.id, { id: vn.node.id, x: vn.x, y: vn.y, w: vn.w, h: vn.h }]),
      );
      newPositions = placeIncremental(newBoxes, next.edges, existing, this.pins);
    } else {
      const positions = await this.runLayout(next);
      if (epoch !== this.layoutEpoch) return;
      newPositions = new Map();
      for (const [id, pos] of positions) {
        const vn = this.scene.nodes.get(id);
        if (vn) {
          if (!vn.pinned) moveTo(this.animator, vn, pos, () => this.scene.markDirty());
        } else {
          newPositions.set(id, pos);
        }
      }
    }

    // 3. add nodes: fade in + scale 0.8→1.0, staggered 30 ms.
    addedNodes.forEach((op, i) => {
      const box = newBoxes[i]!;
      const vn = makeVNode(op.node, box.w, box.h);
      const pos = newPositions.get(op.node.id) ?? { x: 0, y: 0 };
      vn.x = pos.x;
      vn.y = pos.y;
      if (this.pins[op.node.id]) vn.pinned = true;
      this.scene.addNode(vn);
      nodeEnter(this.animator, vn, i, () => this.scene.markDirty());
    });

    // 4. update nodes: swap data + pulse (scope chip flips with a flash).
    const selectedId = this.scene.selectedId;
    let selectedChanged: Set<string> | null = null;
    for (const op of updatedNodes) {
      const vn = this.scene.nodes.get(op.node.id);
      if (!vn) continue;
      const before = vn.node;
      vn.node = op.node;
      const size = measureNode(op.node.displayName);
      vn.w = size.w;
      vn.h = size.h;
      pulse(this.animator, vn, () => this.scene.markDirty());
      if (op.node.id === selectedId) {
        selectedChanged = changedFieldsOf(before, op.node);
      }
    }

    // 5. add edges: 300 ms dash-draw from provider to consumer.
    addedEdges.forEach((op, i) => {
      const ve = makeVEdge(op.edge);
      this.scene.addEdge(ve);
      edgeDraw(this.animator, ve, i, () => this.scene.markDirty());
    });

    this.scene.markDirty();
    this.scene.refreshFocus();
    this.engine.requestRender();

    // Side panel refreshes in place with changed fields highlighted.
    if (selectedId && this.scene.nodes.has(selectedId)) {
      const node = next.nodes.find((n) => n.id === selectedId);
      if (node && selectedChanged) {
        this.sidePanel.show(node, next, next.runtime ?? this.runtime, selectedChanged);
      }
    } else if (selectedId) {
      this.deselect(false); // the inspected node was removed
    }
  }

  /**
   * The deterministic layered ("tree") layout (src/layout/layered.ts) is THE
   * layout: synchronous, dependency-free, overlap-free by construction —
   * nodes are always placed. (elkjs was evaluated and dropped: its internal
   * nested-worker fallback breaks under bundling and it cost 1.4 MB.)
   */
  private async runLayout(snapshot: GraphSnapshot): Promise<Map<string, Position>> {
    const boxes = snapshot.nodes.map((n) => {
      const existing = this.scene.nodes.get(n.id);
      const size = existing ? { w: existing.w, h: existing.h } : measureNode(n.displayName);
      return { id: n.id, ...size };
    });
    const laneOf = deriveLanes(snapshot.nodes, snapshot.edges);
    // Ownership shows as the card's accent color (see NodeRenderer) — the
    // vertical flow itself belongs to the dependency structure.
    for (const [id, lane] of laneOf) {
      const vn = this.scene.nodes.get(id);
      if (vn) vn.lane = lane;
    }
    const positions = layeredLayout(boxes, layoutEdges(snapshot), laneOf);
    for (const [id] of positions) {
      const pin = this.pins[id];
      if (pin) positions.set(id, pin);
    }
    return positions;
  }

  // ------------------------------------------------------------ runtime

  private onRuntimeEvent(event: RuntimeEvent): void {
    if (!this.runtime) this.runtime = { openScopes: [], instances: [] };
    switch (event.type) {
      case 'runtime.instanceCreated': {
        this.runtime.instances.push({
          nodeId: event.nodeId,
          scopeId: event.scopeId,
          createdAt: Date.now(),
          creationMicros: event.creationMicros,
        });
        const vn = this.scene.nodes.get(event.nodeId);
        if (vn) {
          vn.instances++;
          vn.lastCreatedAt = Date.now();
          vn.lastCreationMicros = event.creationMicros;
          vn.lastScopeId = event.scopeId;
          glowFlash(this.animator, vn, () => this.scene.markDirty());
          this.engine.requestRender();
        }
        break;
      }
      case 'runtime.scopeOpened': {
        if (!this.runtime.openScopes.some((s) => s.id === event.scopeId)) {
          this.runtime.openScopes.push({ id: event.scopeId, name: event.name, parent: event.parent ?? null });
        }
        break;
      }
      case 'runtime.scopeClosed': {
        this.runtime.openScopes = this.runtime.openScopes.filter((s) => s.id !== event.scopeId);
        const dropped = this.runtime.instances.filter((i) => i.scopeId === event.scopeId);
        this.runtime.instances = this.runtime.instances.filter((i) => i.scopeId !== event.scopeId);
        for (const inst of dropped) {
          const vn = this.scene.nodes.get(inst.nodeId);
          if (vn) vn.instances = Math.max(0, vn.instances - 1);
        }
        this.engine.requestRender();
        break;
      }
      case 'runtime.resolutionFailed': {
        const vn = this.scene.nodes.get(event.nodeId);
        if (vn) {
          vn.error = event.message;
          errorShake(this.animator, vn, () => this.scene.markDirty());
          this.engine.requestRender();
        }
        this.toasts.show(`resolution failed · ${event.nodeId.split('.').pop()} — ${event.message}`, { kind: 'error', ttlMs: 8000 });
        break;
      }
    }
    this.refreshPanel(true);
  }

  private applyRuntimeToScene(): void {
    for (const vn of this.scene.nodes.values()) {
      vn.instances = 0;
      vn.lastCreatedAt = null;
      vn.lastCreationMicros = null;
      vn.lastScopeId = null;
    }
    if (!this.runtime) return;
    for (const inst of this.runtime.instances) {
      const vn = this.scene.nodes.get(inst.nodeId);
      if (!vn) continue;
      vn.instances++;
      if (vn.lastCreatedAt === null || inst.createdAt > vn.lastCreatedAt) {
        vn.lastCreatedAt = inst.createdAt;
        vn.lastCreationMicros = inst.creationMicros;
        vn.lastScopeId = inst.scopeId;
      }
    }
  }

  // ---------------------------------------------------------- selection

  private selectNode(id: string, opts: { fly: boolean }): void {
    const vn = this.scene.nodes.get(id);
    if (!vn) return;
    this.scene.setFocus(id, this.impactMode);
    this.sidePanel.show(vn.node, this.snapshot, this.runtime);
    if (opts.fly) {
      this.engine.camera.flyToBounds({ x: vn.x - 180, y: vn.y - 140, w: vn.w + 360, h: vn.h + 280 }, 60, 450);
    }
    this.engine.requestRender();
  }

  private deselect(showOverview = true): void {
    this.impactMode = false;
    this.scene.setFocus(null);
    if (this.mode === 'live' && showOverview && !this.overviewDismissed) {
      this.sidePanel.showOverview(this.snapshot, this.runtime);
    } else {
      this.sidePanel.hide();
    }
    this.engine.requestRender();
  }

  private refreshPanel(runtimeOnly = false): void {
    if (!this.sidePanel.visible) return;
    const id = this.sidePanel.shownNodeId;
    if (id) {
      const node = this.snapshot.nodes.find((n) => n.id === id);
      if (node) this.sidePanel.show(node, this.snapshot, this.runtime);
      else this.sidePanel.hide();
    } else if (!runtimeOnly || this.mode === 'live') {
      if (!this.overviewDismissed) this.sidePanel.showOverview(this.snapshot, this.runtime);
    }
  }

  private fit(): void {
    const b = this.scene.bounds();
    if (b) this.engine.camera.fitBounds(b, 90);
    this.engine.requestRender();
  }

  private zoomToSelection(): void {
    const id = this.scene.selectedId;
    if (!id) return;
    const vn = this.scene.nodes.get(id)!;
    const neighborhood = new Set<string>([id]);
    for (const ve of this.scene.edges.values()) {
      if (ve.edge.from === id) neighborhood.add(ve.edge.to);
      if (ve.edge.to === id) neighborhood.add(ve.edge.from);
    }
    const b = this.scene.boundsOf(neighborhood) ?? { x: vn.x, y: vn.y, w: vn.w, h: vn.h };
    this.engine.camera.flyToBounds(b, 100, 450);
    this.engine.requestRender();
  }

  /** Impact mode ('i'): highlight the transitive blast radius of the selection. */
  private toggleImpactMode(): void {
    const id = this.scene.selectedId;
    if (!id) return;
    this.impactMode = !this.impactMode;
    this.scene.setFocus(id, this.impactMode);
    this.toasts.show(
      this.impactMode
        ? 'impact mode — everything this node touches, transitively'
        : 'neighborhood mode — direct connections only',
      { kind: 'info', ttlMs: 2200 },
    );
    this.engine.requestRender();
  }

  /**
   * Arrange: clear every pin and animate all blocks into the computed vertical
   * tree (top-down Sugiyama), then fit. The explicit "make it correct" button —
   * pinned cards otherwise override the layout forever.
   */
  private async arrange(): Promise<void> {
    if (this.snapshot.nodes.length === 0) return;
    this.pins = {};
    if (this.appId) persistence.savePins(this.appId, this.pins);
    for (const vn of this.scene.nodes.values()) vn.pinned = false;

    const epoch = ++this.layoutEpoch;
    const positions = await this.runLayout(this.snapshot);
    if (epoch !== this.layoutEpoch) return;
    for (const [id, pos] of positions) {
      const vn = this.scene.nodes.get(id);
      if (vn) moveTo(this.animator, vn, pos, () => this.scene.markDirty());
    }
    this.scene.markDirty();
    this.scene.refreshFocus();
    this.fit();
    this.toasts.show(`arranged ${positions.size} blocks into the dependency tree`, { kind: 'success', ttlMs: 2500 });
    this.engine.requestRender();
  }

  /** Downloads the current view as a PNG. */
  private exportPng(): void {
    const link = document.createElement('a');
    link.download = `${this.appId ?? 'dependency'}-graph.png`;
    link.href = this.canvas.toDataURL('image/png');
    link.click();
    this.toasts.show('exported PNG of the current view', { kind: 'success', ttlMs: 2200 });
  }

  private applyFilter(f: FilterState): void {
    this.filter = f;
    if (!filterActive(f)) {
      this.scene.setFilterVisible(null);
    } else {
      const visible = new Set<string>();
      for (const n of this.snapshot.nodes) {
        if (nodePassesFilter(n, f)) visible.add(n.id);
      }
      this.scene.setFilterVisible(visible);
    }
    this.engine.requestRender();
  }

  // -------------------------------------------------------- interactions

  private wireInteractions(): void {
    interface DragState {
      pointerId: number;
      mode: 'pan' | 'node';
      lastX: number;
      lastY: number;
      moved: boolean;
      node: VNode | null;
    }
    let drag: DragState | null = null;

    const toWorld = (ev: { clientX: number; clientY: number }) => {
      const rect = this.canvas.getBoundingClientRect();
      return this.engine.camera.screenToWorld({ x: ev.clientX - rect.left, y: ev.clientY - rect.top });
    };

    this.canvas.addEventListener('pointerdown', (ev) => {
      if (ev.button !== 0) return;
      const hit = this.scene.hitTest(toWorld(ev));
      drag = {
        pointerId: ev.pointerId,
        mode: hit ? 'node' : 'pan',
        lastX: ev.clientX,
        lastY: ev.clientY,
        moved: false,
        node: hit,
      };
      this.canvas.setPointerCapture(ev.pointerId);
      if (!hit) this.canvas.classList.add('panning');
    });

    this.canvas.addEventListener('pointermove', (ev) => {
      if (drag && ev.pointerId === drag.pointerId) {
        const dx = ev.clientX - drag.lastX;
        const dy = ev.clientY - drag.lastY;
        if (Math.abs(ev.clientX - drag.lastX) + Math.abs(ev.clientY - drag.lastY) > 0) {
          if (!drag.moved && Math.hypot(dx, dy) < 3) return;
          drag.moved = true;
        }
        drag.lastX = ev.clientX;
        drag.lastY = ev.clientY;
        if (drag.mode === 'pan') {
          this.engine.camera.panBy(dx, dy);
        } else if (drag.node) {
          const scale = this.engine.camera.scale;
          drag.node.x += dx / scale;
          drag.node.y += dy / scale;
          drag.node.pinned = true;
          this.scene.markDirty();
          this.engine.requestRender();
        }
        return;
      }
      // hover
      const hit = this.scene.hitTest(toWorld(ev));
      const hoverId = hit?.node.id ?? null;
      if (hoverId !== this.scene.hoverNodeId) {
        this.scene.hoverNodeId = hoverId;
        this.canvas.classList.toggle('over-node', hoverId !== null);
        this.engine.requestRender();
      }
    });

    const endDrag = (ev: PointerEvent) => {
      if (!drag || ev.pointerId !== drag.pointerId) return;
      this.canvas.classList.remove('panning');
      const d = drag;
      drag = null;
      if (!d.moved) {
        if (d.node) {
          this.overviewDismissed = false;
          this.selectNode(d.node.node.id, { fly: false });
        } else {
          this.deselect();
        }
      } else if (d.mode === 'node' && d.node) {
        // drag a node = pin it; persisted
        this.pins[d.node.node.id] = { x: d.node.x, y: d.node.y };
        if (this.appId) persistence.savePins(this.appId, this.pins);
        this.scene.markDirty();
        this.engine.requestRender();
      }
    };
    this.canvas.addEventListener('pointerup', endDrag);
    this.canvas.addEventListener('pointercancel', endDrag);

    this.canvas.addEventListener(
      'wheel',
      (ev) => {
        ev.preventDefault();
        const rect = this.canvas.getBoundingClientRect();
        const factor = Math.exp(-clamp(ev.deltaY, -220, 220) * (ev.ctrlKey ? 0.008 : 0.0016));
        this.engine.camera.zoomAt(ev.clientX - rect.left, ev.clientY - rect.top, factor);
      },
      { passive: false },
    );

    this.canvas.addEventListener('dblclick', (ev) => {
      const hit = this.scene.hitTest(toWorld(ev));
      if (hit) this.selectNode(hit.node.id, { fly: true });
    });
  }

  private wireKeyboard(): void {
    window.addEventListener('keydown', (ev) => {
      const inInput = ev.target instanceof HTMLInputElement || ev.target instanceof HTMLTextAreaElement;
      if (ev.key === '/' && !inInput) {
        ev.preventDefault();
        this.toolbar.focusSearch();
      } else if (ev.key === '!' && ev.shiftKey && !inInput) {
        ev.preventDefault();
        this.fit();
      } else if (ev.key === '@' && ev.shiftKey && !inInput) {
        ev.preventDefault();
        this.zoomToSelection();
      } else if ((ev.code === 'Digit1' || ev.code === 'Digit2') && ev.shiftKey && !inInput) {
        // layout-independent fallback for Shift+1 / Shift+2
        ev.preventDefault();
        if (ev.code === 'Digit1') this.fit();
        else this.zoomToSelection();
      } else if (ev.key === 'a' && !inInput && !ev.metaKey && !ev.ctrlKey) {
        ev.preventDefault();
        void this.arrange();
      } else if (ev.key === 'i' && !inInput && this.scene.selectedId) {
        ev.preventDefault();
        this.toggleImpactMode();
      } else if (ev.key === '?' && !inInput) {
        ev.preventDefault();
        this.legend.toggle();
      } else if (ev.key === 'Escape' && !inInput) {
        this.legend.hide();
        this.deselect();
      }
    });
  }

  private scheduleCameraSave(): void {
    if (this.cameraSaveTimer !== null) clearTimeout(this.cameraSaveTimer);
    this.cameraSaveTimer = setTimeout(() => {
      this.cameraSaveTimer = null;
      if (this.appId) {
        const { x, y, scale } = this.engine.camera;
        persistence.saveCamera(this.appId, { x, y, scale });
      }
    }, 400);
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

/**
 * Edges as the layout sees them: the real graph plus one synthetic edge from
 * every bound interface satellite down to its implementation — without it the
 * implementation looks like a root and floats to the top row.
 */
function layoutEdges(snapshot: GraphSnapshot): { from: string; to: string }[] {
  const ids = new Set(snapshot.nodes.map((n) => n.id));
  const synthetic = snapshot.nodes.flatMap((n) =>
    n.boundTo.filter((bound) => ids.has(bound)).map((bound) => ({ from: bound, to: n.id })),
  );
  return [...snapshot.edges.map((e) => ({ from: e.from, to: e.to })), ...synthetic];
}

/** Which panel fields changed (for the in-place refresh highlight). */
function changedFieldsOf(before: GraphNode, after: GraphNode): Set<string> {
  const changed = new Set<string>();
  if (!nodeChanged(before, after)) return changed;
  if ((before.scope ?? null) !== (after.scope ?? null)) changed.add('scope');
  if (before.kind !== after.kind) changed.add('kind');
  if ([...before.boundTo].sort().join() !== [...after.boundTo].sort().join()) changed.add('boundTo');
  const p1 = before.providedBy;
  const p2 = after.providedBy;
  if ((p1 === null) !== (p2 === null) || p1?.declaration !== p2?.declaration || p1?.file !== p2?.file || p1?.line !== p2?.line || p1?.gradleModule !== p2?.gradleModule) {
    changed.add('providedBy');
  }
  return changed;
}

const root = document.getElementById('app');
if (!root) throw new Error('missing #app root');
const app = new App(root);
void app.start();
