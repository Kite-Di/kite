/**
 * Mock inspector server — decouples board development from
 * Android builds. Plain Node (run with `npm run mock`, uses
 * --experimental-strip-types) + the `ws` package.
 *
 *  - GET /api/graph  → fixture snapshot with a live runtime section
 *  - GET /api/meta   → { appId, versionName, buildFingerprint, schemaVersion }
 *  - WS  /api/live   → hello → graph.snapshot → scripted runtime events
 *  - every 30 s simulates a rebuild: drops all sockets, mutates the fixture
 *    (add/remove a node, change a scope) and mints a new buildFingerprint so
 *    the client-side diff animation can be exercised against `npm run dev`.
 */

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import type { GraphEdge, GraphNode, GraphSnapshot, LiveInstance, OpenScope } from '../src/model/graph.ts';

const PORT = 8394;
const REBUILD_INTERVAL_MS = 30_000;
const EVENT_INTERVAL_MS = 3_000;

// ---------------------------------------------------------------- fixture

const fixturePath = fileURLToPath(new URL('./fixtures/graph.json', import.meta.url));
const baseSnapshot: GraphSnapshot = JSON.parse(readFileSync(fixturePath, 'utf-8')) as GraphSnapshot;

/** Rebuild variant B: +CrashReporter node/edge, GreetingUseCase gains a scope, SecondPresenter dropped. */
function mutatedSnapshot(): GraphSnapshot {
  const snap: GraphSnapshot = JSON.parse(JSON.stringify(baseSnapshot)) as GraphSnapshot;
  const dropped = 'com.kite.demo.ui.SecondPresenter';
  snap.nodes = snap.nodes.filter((n: GraphNode) => n.id !== dropped);
  snap.edges = snap.edges.filter((e: GraphEdge) => e.from !== dropped && e.to !== dropped);

  snap.nodes = snap.nodes.map((n: GraphNode) =>
    n.id === 'com.kite.demo.ui.GreetingUseCase' ? { ...n, scope: 'ActivityScoped' } : n,
  );

  const crashReporter: GraphNode = {
    id: 'com.kite.demo.data.CrashReporter',
    type: 'com.kite.demo.data.CrashReporter',
    displayName: 'CrashReporter',
    kind: 'injectable',
    scope: 'Singleton',
    boundTo: [],
    providedBy: {
      declaration: 'CrashReporter',
      gradleModule: ':app',
      file: 'app/src/main/java/com/kite/demo/data/CrashReporter.kt',
      line: 7,
    },
  };
  snap.nodes.push(crashReporter);
  snap.edges.push({
    id: `com.kite.demo.MainActivity -> ${crashReporter.id} # 0`,
    from: 'com.kite.demo.MainActivity',
    to: crashReporter.id,
    siteKind: 'field',
    paramName: 'crashReporter',
    deferred: 'none',
    site: { file: 'app/src/main/java/com/kite/demo/MainActivity.kt', line: 31 },
  });
  snap.edges.push({
    id: `${crashReporter.id} -> com.kite.demo.data.Analytics # 0`,
    from: crashReporter.id,
    to: 'com.kite.demo.data.Analytics',
    siteKind: 'constructorParam',
    paramName: 'analytics',
    deferred: 'provider',
    site: { file: 'app/src/main/java/com/kite/demo/data/CrashReporter.kt', line: 8 },
  });
  return snap;
}

// ------------------------------------------------------------ build state

let buildIndex = 0;

function currentSnapshot(): GraphSnapshot {
  return buildIndex % 2 === 0 ? baseSnapshot : mutatedSnapshot();
}

function buildFingerprint(): string {
  return createHash('sha1')
    .update(`build-${buildIndex}:${JSON.stringify(currentSnapshot().nodes.map((n: GraphNode) => n.id))}`)
    .digest('hex')
    .slice(0, 12);
}

// ----------------------------------------------------------- runtime sim

interface RuntimeSim {
  openScopes: OpenScope[];
  instances: LiveInstance[];
}

let runtime: RuntimeSim = freshRuntime();
let activityCounter = 1;

function freshRuntime(): RuntimeSim {
  return {
    openScopes: [{ id: 'app', name: 'Singleton', parent: null }],
    instances: [],
  };
}

function snapshotWithRuntime(): GraphSnapshot {
  return { ...currentSnapshot(), runtime: { openScopes: runtime.openScopes, instances: runtime.instances } };
}

// -------------------------------------------------------------- protocol

interface Session {
  ws: WebSocket;
  seq: number;
  eventTimer: ReturnType<typeof setInterval> | null;
  scriptStep: number;
}

const sessions = new Set<Session>();

function send(session: Session, msg: Record<string, unknown>, skipSeq = false): void {
  if (session.ws.readyState !== WebSocket.OPEN) return;
  if (skipSeq) session.seq++; // deliberately burn a seq number → client resyncs
  session.ws.send(JSON.stringify({ ...msg, seq: session.seq++ }));
}

function sendHelloAndSnapshot(session: Session): void {
  send(session, {
    type: 'hello',
    schemaVersion: baseSnapshot.schemaVersion,
    appId: baseSnapshot.appId,
    variant: baseSnapshot.variant,
    buildFingerprint: buildFingerprint(),
  });
  send(session, { type: 'graph.snapshot', snapshot: snapshotWithRuntime() });
}

/** Scripted runtime events: creations, scope open/close, an occasional failure. */
function nextScriptedEvent(session: Session): void {
  const snap = currentSnapshot();
  const step = session.scriptStep++;
  const singletons = snap.nodes.filter((n: GraphNode) => n.scope === 'Singleton');
  const activityScoped = snap.nodes.filter((n: GraphNode) => n.scope === 'ActivityScoped');

  const phase = step % 8;
  if (phase < 3) {
    // create a singleton instance (skip ones already created)
    const candidate = singletons.find(
      (n: GraphNode) => !runtime.instances.some((i: LiveInstance) => i.nodeId === n.id),
    ) ?? singletons[step % Math.max(1, singletons.length)];
    if (!candidate) return;
    const micros = 80 + Math.floor(Math.random() * 900);
    if (!runtime.instances.some((i) => i.nodeId === candidate.id && i.scopeId === 'app')) {
      runtime.instances.push({ nodeId: candidate.id, scopeId: 'app', createdAt: Date.now(), creationMicros: micros });
    }
    broadcast({ type: 'runtime.instanceCreated', nodeId: candidate.id, scopeId: 'app', creationMicros: micros });
  } else if (phase === 3) {
    const scopeId = `MainActivity@${(activityCounter++).toString(16).padStart(4, '0')}`;
    runtime.openScopes.push({ id: scopeId, name: 'ActivityScoped', parent: 'app' });
    broadcast({ type: 'runtime.scopeOpened', scopeId, name: 'ActivityScoped', parent: 'app' });
  } else if (phase === 4 || phase === 5) {
    const scope = runtime.openScopes.find((s: OpenScope) => s.name === 'ActivityScoped');
    const node = activityScoped[step % Math.max(1, activityScoped.length)];
    if (scope && node) {
      const micros = 120 + Math.floor(Math.random() * 1500);
      runtime.instances.push({ nodeId: node.id, scopeId: scope.id, createdAt: Date.now(), creationMicros: micros });
      broadcast({ type: 'runtime.instanceCreated', nodeId: node.id, scopeId: scope.id, creationMicros: micros });
    }
  } else if (phase === 6) {
    const scope = runtime.openScopes.find((s: OpenScope) => s.name === 'ActivityScoped');
    if (scope) {
      runtime.openScopes = runtime.openScopes.filter((s: OpenScope) => s.id !== scope.id);
      runtime.instances = runtime.instances.filter((i: LiveInstance) => i.scopeId !== scope.id);
      broadcast({ type: 'runtime.scopeClosed', scopeId: scope.id });
    }
  } else {
    // occasional failure — and occasionally burn a seq to exercise resync
    if (step % 24 === 7) {
      const target = snap.nodes[step % snap.nodes.length];
      if (target) {
        broadcast({
          type: 'runtime.resolutionFailed',
          nodeId: target.id,
          scopePath: 'app',
          message: 'no provider bound in the current scope (simulated)',
        });
      }
    } else if (step % 16 === 15) {
      send(session, { type: 'runtime.instanceCreated', nodeId: singletons[0]?.id ?? '', scopeId: 'app', creationMicros: 99 }, true);
      console.log('[mock] burned a seq number to trigger client resync');
    }
  }
}

function broadcast(msg: Record<string, unknown>): void {
  for (const session of sessions) send(session, msg);
}

// ------------------------------------------------------------- rebuilds

setInterval(() => {
  buildIndex++;
  runtime = freshRuntime();
  activityCounter = 1;
  mockDecisionApplied = false;
  console.log(
    `[mock] rebuild #${buildIndex} (${buildIndex % 2 === 0 ? 'base' : 'mutated'} fixture) → fingerprint ${buildFingerprint()} — dropping ${sessions.size} socket(s)`,
  );
  for (const session of sessions) {
    session.ws.terminate();
  }
}, REBUILD_INTERVAL_MS);

// ----------------------------------------------------------- http server

function json(res: ServerResponse, body: unknown, status = 200): void {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json',
    'access-control-allow-origin': '*',
  });
  res.end(data);
}

// Decision-cards demo: every mutated build pretends UserRepository
// gained a second implementation. Applying just flips in-memory state — the
// mock never writes files; the real write path lives in board/server.ts.
let mockDecisionApplied = false;

function mockDecisions(): Record<string, unknown> {
  const subject = 'com.kite.demo.data.UserRepository';
  const pending = buildIndex % 2 === 1 && !mockDecisionApplied
    ? [{
        id: `bind:${subject}`,
        kind: 'bind',
        title: 'UserRepository has 2 implementations',
        subject,
        consumers: ['GreetingUseCase, constructor param \'repository\' (app/src/main/java/…/Presenters.kt:15)'],
        candidates: [
          {
            fqn: `${subject.substring(0, subject.lastIndexOf('.'))}.NetworkUserRepository`,
            displayName: 'NetworkUserRepository',
            file: 'app/src/main/java/com/kite/demo/data/UserRepository.kt',
            line: 12,
            insert: `@Bind(${subject}::class, to = ${subject.substring(0, subject.lastIndexOf('.'))}.NetworkUserRepository::class)`,
          },
          {
            fqn: `${subject.substring(0, subject.lastIndexOf('.'))}.FakeUserRepository`,
            displayName: 'FakeUserRepository',
            file: 'app/src/main/java/com/kite/demo/data/UserRepository.kt',
            line: 21,
            insert: `@Bind(${subject}::class, to = ${subject.substring(0, subject.lastIndexOf('.'))}.FakeUserRepository::class)`,
          },
        ],
      }]
    : [];
  return {
    module: ':app',
    rulesFile: 'app/src/main/java/com/kite/demo/di/GraphRules.kt',
    rulesObject: 'GraphRules',
    pending,
  };
}

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  if (url.pathname === '/api/graph') {
    json(res, snapshotWithRuntime());
  } else if (url.pathname === '/api/decisions') {
    json(res, mockDecisions());
  } else if (url.pathname === '/api/decisions/apply' && req.method === 'POST') {
    mockDecisionApplied = true;
    console.log('[mock] decision applied (simulated — no file written)');
    json(res, { ok: true, file: 'app/src/main/java/com/kite/demo/di/GraphRules.kt', line: 18 });
  } else if (url.pathname === '/api/meta') {
    json(res, {
      appId: baseSnapshot.appId,
      versionName: '1.0.0-mock',
      buildFingerprint: buildFingerprint(),
      schemaVersion: baseSnapshot.schemaVersion,
    });
  } else {
    json(res, { error: 'not found' }, 404);
  }
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  if (url.pathname !== '/api/live') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws: WebSocket) => {
  const session: Session = { ws, seq: 0, eventTimer: null, scriptStep: 0 };
  sessions.add(session);
  console.log(`[mock] client connected (${sessions.size} total)`);

  sendHelloAndSnapshot(session);
  session.eventTimer = setInterval(() => nextScriptedEvent(session), EVENT_INTERVAL_MS);

  ws.on('message', (raw: Buffer) => {
    let msg: { type?: string };
    try {
      msg = JSON.parse(raw.toString()) as { type?: string };
    } catch {
      return;
    }
    if (msg.type === 'ping') {
      send(session, { type: 'pong' });
    } else if (msg.type === 'resync') {
      console.log('[mock] client requested resync');
      send(session, { type: 'graph.snapshot', snapshot: snapshotWithRuntime() });
    }
  });

  ws.on('close', () => {
    if (session.eventTimer) clearInterval(session.eventTimer);
    sessions.delete(session);
    console.log(`[mock] client disconnected (${sessions.size} left)`);
  });
});

server.listen(PORT, () => {
  console.log(`[mock] inspector mock listening on http://localhost:${PORT}`);
  console.log(`[mock]   GET  /api/graph   GET /api/meta   WS /api/live`);
  console.log(`[mock]   simulated rebuild every ${REBUILD_INTERVAL_MS / 1000}s`);
  console.log(`[mock] run "npm run dev" and open the Vite URL — /api is proxied here`);
});
