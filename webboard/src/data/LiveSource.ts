/**
 * WebSocket client for `ws://<host>/api/live`.
 *
 * - expects `hello` (carries buildFingerprint) then `graph.snapshot`
 * - runtime.* events stream afterwards
 * - every server message carries `seq`; on a gap → send `{"type":"resync"}`
 * - ping keepalive every 30 s
 * - reconnect with exponential backoff 1 s → 10 s cap
 */

import { parseSnapshot, type GraphSnapshot, type PatchOp } from '../model/graph';

export interface HelloMessage {
  type: 'hello';
  seq: number;
  schemaVersion: number;
  appId: string;
  variant: string;
  buildFingerprint: string;
}

export interface InstanceCreatedEvent {
  type: 'runtime.instanceCreated';
  nodeId: string;
  scopeId: string;
  creationMicros: number;
}

export interface ScopeOpenedEvent {
  type: 'runtime.scopeOpened';
  scopeId: string;
  name: string;
  parent?: string | null;
}

export interface ScopeClosedEvent {
  type: 'runtime.scopeClosed';
  scopeId: string;
}

export interface ResolutionFailedEvent {
  type: 'runtime.resolutionFailed';
  nodeId: string;
  scopePath?: string;
  message: string;
}

export interface ViewModelResolvedEvent {
  type: 'runtime.viewModelResolved';
  /** The ViewModel's node id. */
  nodeId: string;
  /** FQN of the resolving screen's class (Activity/Fragment). */
  ownerId: string;
  ownerDisplay: string;
}

export type RuntimeEvent =
  | InstanceCreatedEvent
  | ScopeOpenedEvent
  | ScopeClosedEvent
  | ResolutionFailedEvent
  | ViewModelResolvedEvent;

export type ConnectionStatus = 'connecting' | 'live' | 'disconnected';

export interface LiveSourceCallbacks {
  onHello?: (hello: HelloMessage) => void;
  onSnapshot?: (snapshot: GraphSnapshot, hello: HelloMessage | null) => void;
  onPatch?: (ops: PatchOp[]) => void;
  onRuntimeEvent?: (event: RuntimeEvent) => void;
  onStatus?: (status: ConnectionStatus) => void;
  /** seq gap detected → resync sent; UI shows an "events dropped" indicator. */
  onEventsDropped?: () => void;
  onError?: (message: string) => void;
}

const PING_INTERVAL_MS = 30_000;
const BACKOFF_MIN_MS = 1_000;
const BACKOFF_MAX_MS = 10_000;

export class LiveSource {
  private ws: WebSocket | null = null;
  private lastSeq = -1;
  private backoffMs = BACKOFF_MIN_MS;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private closed = false;
  private hello: HelloMessage | null = null;

  constructor(
    private readonly url: string,
    private readonly cb: LiveSourceCallbacks,
  ) {}

  /** Builds the live WS url for the current page origin. */
  static defaultUrl(): string {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${location.host}/api/live`;
  }

  connect(): void {
    this.closed = false;
    this.open();
  }

  dispose(): void {
    this.closed = true;
    this.clearTimers();
    this.ws?.close();
    this.ws = null;
  }

  get buildFingerprint(): string | null {
    return this.hello?.buildFingerprint ?? null;
  }

  private open(): void {
    if (this.closed) return;
    this.cb.onStatus?.('connecting');
    this.lastSeq = -1;

    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      this.backoffMs = BACKOFF_MIN_MS;
      this.startPing();
    };
    ws.onmessage = (ev) => this.handleFrame(String(ev.data));
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.clearTimers();
      if (!this.closed) {
        this.cb.onStatus?.('disconnected');
        this.scheduleReconnect();
      }
    };
    ws.onerror = () => {
      // onclose follows; nothing to do here.
    };
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer !== null) return;
    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, BACKOFF_MAX_MS);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, delay);
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => this.send({ type: 'ping' }), PING_INTERVAL_MS);
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private clearTimers(): void {
    this.stopPing();
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private send(msg: Record<string, unknown>): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  private handleFrame(text: string): void {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(text) as Record<string, unknown>;
    } catch {
      this.cb.onError?.('malformed live frame');
      return;
    }
    const type = msg['type'];
    const seq = msg['seq'];
    if (typeof type !== 'string' || typeof seq !== 'number') return;

    // Sequence-gap detection: resync on any gap.
    if (this.lastSeq >= 0 && seq !== this.lastSeq + 1) {
      this.lastSeq = seq;
      this.cb.onEventsDropped?.();
      this.send({ type: 'resync' });
      return;
    }
    this.lastSeq = seq;

    switch (type) {
      case 'hello': {
        this.hello = {
          type: 'hello',
          seq,
          schemaVersion: Number(msg['schemaVersion']),
          appId: String(msg['appId']),
          variant: String(msg['variant']),
          buildFingerprint: String(msg['buildFingerprint']),
        };
        this.cb.onHello?.(this.hello);
        break;
      }
      case 'graph.snapshot': {
        try {
          const snapshot = parseSnapshot(msg['snapshot']);
          this.cb.onStatus?.('live');
          this.cb.onSnapshot?.(snapshot, this.hello);
        } catch (e) {
          this.cb.onError?.(e instanceof Error ? e.message : 'bad snapshot');
        }
        break;
      }
      case 'graph.patch': {
        const ops = msg['ops'];
        if (Array.isArray(ops)) this.cb.onPatch?.(ops as PatchOp[]);
        break;
      }
      case 'runtime.instanceCreated':
        this.cb.onRuntimeEvent?.({
          type: 'runtime.instanceCreated',
          nodeId: String(msg['nodeId']),
          scopeId: String(msg['scopeId']),
          creationMicros: Number(msg['creationMicros']),
        });
        break;
      case 'runtime.scopeOpened':
        this.cb.onRuntimeEvent?.({
          type: 'runtime.scopeOpened',
          scopeId: String(msg['scopeId']),
          name: String(msg['name']),
          parent: msg['parent'] == null ? null : String(msg['parent']),
        });
        break;
      case 'runtime.scopeClosed':
        this.cb.onRuntimeEvent?.({ type: 'runtime.scopeClosed', scopeId: String(msg['scopeId']) });
        break;
      case 'runtime.resolutionFailed':
        this.cb.onRuntimeEvent?.({
          type: 'runtime.resolutionFailed',
          nodeId: String(msg['nodeId']),
          scopePath: msg['scopePath'] == null ? undefined : String(msg['scopePath']),
          message: String(msg['message']),
        });
        break;
      case 'runtime.viewModelResolved':
        this.cb.onRuntimeEvent?.({
          type: 'runtime.viewModelResolved',
          nodeId: String(msg['nodeId']),
          ownerId: String(msg['ownerId']),
          ownerDisplay: String(msg['ownerDisplay']),
        });
        break;
      case 'pong':
        break;
      default:
        // Forward-compatible: unknown message types are ignored.
        break;
    }
  }
}

/**
 * Live/static mode detection: if the page origin serves
 * /api/graph, the board runs live against the inspector; otherwise static.
 */
export async function detectLiveMode(timeoutMs = 2500): Promise<GraphSnapshot | null> {
  try {
    const res = await fetch('/api/graph', { signal: AbortSignal.timeout(timeoutMs) });
    if (!res.ok) return null;
    return parseSnapshot(await res.json());
  } catch {
    return null;
  }
}
