/**
 * TypeScript mirror of the graph JSON schema produced by
 * `kite/graph-core` (GraphModel.kt). Kept in sync by `graph.test.ts`,
 * which parses the Kotlin golden file through these types.
 */

export const SUPPORTED_SCHEMA_VERSIONS: readonly number[] = [1, 2];

/** Node kinds. `entryPoint` = member-injection target (e.g. an Activity). */
export type NodeKind = 'injectable' | 'provides' | 'boundInterface' | 'entryPoint' | 'set' | 'map' | 'external';

/**
 * `binds` and `viewModel` never appear in graph.json (edges on the wire are
 * injection sites only) — they are derived client-side by {@link withDerivedEdges}.
 */
export type SiteKind =
  | 'constructorParam'
  | 'field'
  | 'providesParam'
  | 'setContribution'
  | 'mapContribution'
  | 'binds'
  | 'viewModel';

export type Deferred = 'none' | 'provider' | 'lazy';

/** "Where it is provided from" — declaration + provenance. */
export interface Provenance {
  declaration: string;
  gradleModule: string;
  file: string;
  line: number;
}

/** "Where it is injected in" — the consuming source location. */
export interface SourceSite {
  file: string;
  line: number;
}

export interface GraphNode {
  /** Stable id: `<type-fqn>` or `<qualifier>@<type-fqn>`. */
  id: string;
  /** Key type FQN. */
  type: string;
  qualifier?: string | null;
  /** Simple name, for rendering. */
  displayName: string;
  kind: NodeKind;
  /** null / absent = unscoped. */
  scope?: string | null;
  /** Extra keys (bindTo). */
  boundTo: string[];
  providedBy?: Provenance | null;
  /** Why the node is wired (schema v2): 'implementation' | 'root-rule' | 'closure'. */
  inferredBy?: string | null;
}

export interface GraphEdge {
  /** `<from-id> -> <to-id> # <siteIndex>`. */
  id: string;
  /** Consumer node id. */
  from: string;
  /** Dependency node id. */
  to: string;
  siteKind: SiteKind;
  paramName?: string | null;
  deferred: Deferred;
  site?: SourceSite | null;
}

export interface ScopeDef {
  name: string;
  level: number;
}

export interface OpenScope {
  id: string;
  name: string;
  parent?: string | null;
}

export interface LiveInstance {
  nodeId: string;
  scopeId: string;
  /** Epoch millis. */
  createdAt: number;
  creationMicros: number;
}

/** "This screen resolved this ViewModel" — accumulated by the inspector. */
export interface ViewModelUsage {
  /** The ViewModel's node id. */
  nodeId: string;
  /** FQN of the resolving ViewModelStoreOwner's class (Activity/Fragment). */
  ownerId: string;
  ownerDisplay: string;
}

/** Present only when served by the inspector (live mode); null in the build artifact. */
export interface RuntimeState {
  openScopes: OpenScope[];
  instances: LiveInstance[];
  /** Absent in pre-usage snapshots (persisted sessions, old inspectors). */
  viewModelUsages?: ViewModelUsage[];
}

export interface GraphSnapshot {
  schemaVersion: number;
  generatedAt?: string;
  appId: string;
  variant: string;
  scopes: ScopeDef[];
  nodes: GraphNode[];
  edges: GraphEdge[];
  runtime?: RuntimeState | null;
}

/**
 * PatchOp — shared shape with the live protocol; also used
 * client-side for animating snapshot diffs.
 */
export type PatchOp =
  | { op: 'addNode'; node: GraphNode }
  | { op: 'updateNode'; node: GraphNode }
  | { op: 'removeNode'; nodeId: string }
  | { op: 'addEdge'; edge: GraphEdge }
  | { op: 'removeEdge'; edgeId: string };

/** Error thrown by {@link parseSnapshot} for malformed / unsupported payloads. */
export class SnapshotParseError extends Error {
  constructor(
    message: string,
    readonly schemaVersion?: number,
  ) {
    super(message);
    this.name = 'SnapshotParseError';
  }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/**
 * Validates the shape of an untrusted JSON value and returns it typed.
 * Light structural validation — enough to fail fast on garbage or a future
 * schema version (unknown schemaVersion → blocking error card).
 */
export function parseSnapshot(raw: unknown): GraphSnapshot {
  if (!isRecord(raw)) throw new SnapshotParseError('snapshot is not a JSON object');
  const v = raw['schemaVersion'];
  if (typeof v !== 'number') throw new SnapshotParseError('missing schemaVersion');
  if (!SUPPORTED_SCHEMA_VERSIONS.includes(v)) {
    throw new SnapshotParseError(`unsupported schemaVersion ${v}`, v);
  }
  if (typeof raw['appId'] !== 'string') throw new SnapshotParseError('missing appId');
  if (typeof raw['variant'] !== 'string') throw new SnapshotParseError('missing variant');
  if (!Array.isArray(raw['nodes'])) throw new SnapshotParseError('missing nodes[]');
  if (!Array.isArray(raw['edges'])) throw new SnapshotParseError('missing edges[]');
  if (!Array.isArray(raw['scopes'])) throw new SnapshotParseError('missing scopes[]');
  for (const n of raw['nodes'] as unknown[]) {
    if (!isRecord(n) || typeof n['id'] !== 'string' || typeof n['displayName'] !== 'string' || typeof n['kind'] !== 'string') {
      throw new SnapshotParseError('malformed node entry');
    }
  }
  for (const e of raw['edges'] as unknown[]) {
    if (!isRecord(e) || typeof e['id'] !== 'string' || typeof e['from'] !== 'string' || typeof e['to'] !== 'string') {
      throw new SnapshotParseError('malformed edge entry');
    }
  }
  return raw as unknown as GraphSnapshot;
}

export function bindsEdgeId(interfaceId: string, implId: string): string {
  return `${interfaceId} -> ${implId} # binds`;
}

export function viewModelEdgeId(ownerId: string, vmId: string): string {
  return `${ownerId} -> ${vmId} # vm`;
}

export function makeBindsEdge(interfaceId: string, implId: string): GraphEdge {
  return { id: bindsEdgeId(interfaceId, implId), from: interfaceId, to: implId, siteKind: 'binds', deferred: 'none' };
}

export function makeViewModelEdge(ownerId: string, vmId: string): GraphEdge {
  return { id: viewModelEdgeId(ownerId, vmId), from: ownerId, to: vmId, siteKind: 'viewModel', deferred: 'none' };
}

/** The synthesized card for a screen that resolved a ViewModel. */
export function makeOwnerNode(usage: ViewModelUsage): GraphNode {
  return {
    id: usage.ownerId,
    type: usage.ownerId,
    displayName: usage.ownerDisplay,
    kind: 'entryPoint',
    boundTo: [],
  };
}

/**
 * Materializes the two relations the wire format carries outside `edges`
 * so rendering, focus, diffing and the side panel all see them as
 * ordinary edges:
 *
 *  - `binds` — one edge per `node.boundTo` entry: interface satellite → its
 *    implementation.
 *  - `viewModel` — one edge (plus a synthesized `entryPoint` card for the
 *    screen) per `runtime.viewModelUsages` entry: screen → ViewModel.
 *
 * Idempotent by stable ids: enriching an already-enriched snapshot (a persisted
 * session) adds nothing, and identical inputs derive identical edges, so
 * snapshot diffs stay quiet across reconnects.
 */
export function withDerivedEdges(snapshot: GraphSnapshot): GraphSnapshot {
  const nodeIds = new Set(snapshot.nodes.map((n) => n.id));
  const edgeIds = new Set(snapshot.edges.map((e) => e.id));

  const nodes = [...snapshot.nodes];
  const edges = [...snapshot.edges];

  for (const node of snapshot.nodes) {
    for (const bound of node.boundTo) {
      const id = bindsEdgeId(bound, node.id);
      if (nodeIds.has(bound) && !edgeIds.has(id)) {
        edgeIds.add(id);
        edges.push(makeBindsEdge(bound, node.id));
      }
    }
  }

  for (const usage of snapshot.runtime?.viewModelUsages ?? []) {
    if (!nodeIds.has(usage.nodeId)) continue; // stale usage from another build
    if (!nodeIds.has(usage.ownerId)) {
      nodeIds.add(usage.ownerId);
      nodes.push(makeOwnerNode(usage));
    }
    const id = viewModelEdgeId(usage.ownerId, usage.nodeId);
    if (!edgeIds.has(id)) {
      edgeIds.add(id);
      edges.push(makeViewModelEdge(usage.ownerId, usage.nodeId));
    }
  }

  if (nodes.length === snapshot.nodes.length && edges.length === snapshot.edges.length) return snapshot;
  return { ...snapshot, nodes, edges };
}

/** An empty snapshot placeholder (used before any data arrives). */
export function emptySnapshot(): GraphSnapshot {
  return {
    schemaVersion: 1,
    appId: '',
    variant: '',
    scopes: [],
    nodes: [],
    edges: [],
    runtime: null,
  };
}
