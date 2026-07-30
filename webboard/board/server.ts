/**
 * Host-side dev board server — the default way to view the dependency graph.
 *
 * The graph NEVER ships in an APK and is NOT viewable on the phone: the KSP
 * processor writes it to `app/build/kite/graph.json` on the development
 * machine, and this server (also on the development machine) serves the built
 * web board and watches that file. Every rebuild in Android Studio rewrites the
 * file; this server then drops live sockets, the board reconnects, receives a
 * new buildFingerprint + snapshot, and animates the diff.
 *
 *   npm run board       (builds the board, then serves http://localhost:8394)
 *
 * Options: GRAPH_FILE=/path/to/graph.json PORT=8394
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { emptyDecisions, parseDecisions, type DecisionsFile } from '../src/model/decisions.ts';
import type { GraphSnapshot } from '../src/model/graph.ts';
import { insertRule, newRulesFile } from '../src/model/rulesEdit.ts';

const PORT = Number(process.env['PORT'] ?? 8394);
const WATCH_INTERVAL_MS = 700;

const boardRoot = fileURLToPath(new URL('..', import.meta.url));
const distDir = join(boardRoot, 'dist');
const graphFile = resolve(
  process.env['GRAPH_FILE'] ?? join(boardRoot, '..', 'app', 'build', 'kite', 'graph.json'),
);
// decisions.json sits next to graph.json; rule paths inside it
// are repo-root-relative, resolved against the repo this board serves.
const decisionsFile = resolve(process.env['DECISIONS_FILE'] ?? join(dirname(graphFile), 'decisions.json'));
const repoRoot = resolve(process.env['REPO_ROOT'] ?? join(boardRoot, '..'));

// ------------------------------------------------------------- graph state

const emptySnapshot: GraphSnapshot = {
  schemaVersion: 1,
  appId: 'unknown',
  variant: 'debug',
  scopes: [],
  nodes: [],
  edges: [],
};

let graphText: string | null = null;
let fingerprint = 'no-graph';

function readGraph(): void {
  try {
    graphText = readFileSync(graphFile, 'utf-8');
    fingerprint = createHash('sha1').update(graphText).digest('hex').slice(0, 12);
  } catch {
    graphText = null;
    fingerprint = 'no-graph';
  }
}

function snapshot(): GraphSnapshot {
  if (graphText === null) return emptySnapshot;
  try {
    return JSON.parse(graphText) as GraphSnapshot;
  } catch {
    return emptySnapshot;
  }
}

readGraph();

// -------------------------------------------------- decision cards

function readDecisions(): DecisionsFile {
  try {
    return parseDecisions(JSON.parse(readFileSync(decisionsFile, 'utf-8')));
  } catch {
    return emptyDecisions();
  }
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((res, rej) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => res(Buffer.concat(chunks).toString('utf-8')));
    req.on('error', rej);
  });
}

/**
 * A card click: writes the chosen candidate's rule line into the module's
 * GraphRules.kt (creating it when the module has none). The edit target and
 * content come from OUR read of decisions.json — the client only names a
 * decision id and a candidate; it can neither choose paths nor inject code.
 */
function applyDecision(body: { id?: unknown; candidateFqn?: unknown }): { status: number; payload: Record<string, unknown> } {
  const decisions = readDecisions();
  const decision = decisions.pending.find((p) => p.id === body.id);
  if (!decision) return { status: 404, payload: { error: 'unknown decision — a rebuild may have resolved it' } };
  const candidate = decision.candidates.find((c) => c.fqn === body.candidateFqn);
  if (!candidate) return { status: 404, payload: { error: 'unknown candidate for that decision' } };

  const relTarget = decisions.rulesFile ?? decisions.suggestedFile;
  if (!relTarget) return { status: 500, payload: { error: 'decisions.json names no rules file target' } };
  const target = resolve(repoRoot, relTarget);
  if (!target.startsWith(repoRoot + sep)) {
    return { status: 400, payload: { error: 'rules file target escapes the repository' } };
  }

  try {
    let line: number;
    if (existsSync(target)) {
      const edited = insertRule(readFileSync(target, 'utf-8'), decisions.rulesObject ?? 'GraphRules', candidate.insert);
      writeFileSync(target, edited.source);
      line = edited.line;
    } else {
      const content = newRulesFile(decisions.suggestedPackage ?? '', candidate.insert);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, content);
      line = content.split('\n').findIndex((l) => l.trim() === candidate.insert.trim()) + 1;
    }
    console.log(`[board] decision ${decision.id} → ${candidate.displayName}; wrote ${relTarget}:${line}`);
    return { status: 200, payload: { ok: true, file: relTarget, line } };
  } catch (e) {
    return { status: 500, payload: { error: e instanceof Error ? e.message : String(e) } };
  }
}

// -------------------------------------------------------------- file watch

let lastMtime = 0;
let lastFingerprint = fingerprint;

setInterval(() => {
  let mtime = 0;
  try {
    mtime = statSync(graphFile).mtimeMs;
  } catch {
    // file gone (clean build in progress) — keep serving the last snapshot
    return;
  }
  if (mtime === lastMtime) return;
  lastMtime = mtime;
  readGraph();
  if (fingerprint === lastFingerprint) return;
  lastFingerprint = fingerprint;
  console.log(`[board] graph changed → fingerprint ${fingerprint}; reconnecting ${wss.clients.size} client(s)`);
  // Same mechanism as an app restart: drop sockets, clients reconnect, get a new
  // hello + snapshot, and animate the diff.
  for (const client of wss.clients) client.terminate();
}, WATCH_INTERVAL_MS);

// ------------------------------------------------------------- http server

const CONTENT_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function json(res: ServerResponse, body: unknown, status = 200): void {
  res.writeHead(status, {
    'content-type': 'application/json',
    'access-control-allow-origin': '*',
    'cache-control': 'no-store',
  });
  res.end(JSON.stringify(body));
}

function serveStatic(res: ServerResponse, pathname: string): void {
  const rel = pathname === '/' ? 'index.html' : pathname.slice(1);
  const file = join(distDir, rel);
  const fallback = join(distDir, 'index.html');
  const target = existsSync(file) && statSync(file).isFile() ? file : fallback;
  if (!existsSync(target)) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('Board bundle missing — run `npm run build` first (or use `npm run board`).');
    return;
  }
  res.writeHead(200, {
    'content-type': CONTENT_TYPES[extname(target)] ?? 'application/octet-stream',
    'cache-control': 'no-store', // dev tool: a stale bundle must never survive a reload
  });
  res.end(readFileSync(target));
}

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  if (url.pathname === '/api/graph') {
    json(res, snapshot());
  } else if (url.pathname === '/api/decisions') {
    json(res, readDecisions());
  } else if (url.pathname === '/api/decisions/apply' && req.method === 'POST') {
    void readBody(req)
      .then((raw) => {
        let body: { id?: unknown; candidateFqn?: unknown };
        try {
          body = JSON.parse(raw) as typeof body;
        } catch {
          json(res, { error: 'malformed JSON body' }, 400);
          return;
        }
        const { status, payload } = applyDecision(body);
        json(res, payload, status);
      })
      .catch(() => json(res, { error: 'could not read request body' }, 500));
  } else if (url.pathname === '/api/meta') {
    json(res, {
      appId: snapshot().appId,
      versionName: 'dev',
      buildFingerprint: fingerprint,
      schemaVersion: snapshot().schemaVersion,
    });
  } else {
    serveStatic(res, url.pathname);
  }
});

// --------------------------------------------------------------- live WS

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  if (url.pathname !== '/api/live') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

wss.on('connection', (ws: WebSocket) => {
  let seq = 0;
  const send = (msg: Record<string, unknown>): void => {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ ...msg, seq: seq++ }));
  };
  const sendSnapshot = (): void => send({ type: 'graph.snapshot', snapshot: snapshot() });

  send({
    type: 'hello',
    schemaVersion: snapshot().schemaVersion,
    appId: snapshot().appId,
    variant: snapshot().variant,
    buildFingerprint: fingerprint,
  });
  sendSnapshot();

  ws.on('message', (raw: Buffer) => {
    let msg: { type?: string };
    try {
      msg = JSON.parse(raw.toString()) as { type?: string };
    } catch {
      return;
    }
    if (msg.type === 'ping') send({ type: 'pong' });
    else if (msg.type === 'resync') sendSnapshot();
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[board] dependency board → http://localhost:${PORT}`);
  console.log(`[board] watching ${graphFile}`);
  console.log(`[board] decision cards from ${decisionsFile} (writes GraphRules.kt on click)`);
  if (graphText === null) {
    console.log('[board] graph not found yet — build the app once in Android Studio (any debug build)');
  } else {
    console.log(`[board] graph loaded: ${snapshot().nodes.length} nodes, fingerprint ${fingerprint}`);
  }
  console.log('[board] rebuild the app in Android Studio and the open board updates automatically');
});
