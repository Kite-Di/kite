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
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import type { GraphSnapshot } from '../src/model/graph.ts';

const PORT = Number(process.env['PORT'] ?? 8394);
const WATCH_INTERVAL_MS = 700;

const boardRoot = fileURLToPath(new URL('..', import.meta.url));
const distDir = join(boardRoot, 'dist');
const graphFile = resolve(
  process.env['GRAPH_FILE'] ?? join(boardRoot, '..', 'app', 'build', 'kite', 'graph.json'),
);

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
  res.writeHead(status, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
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
  res.writeHead(200, { 'content-type': CONTENT_TYPES[extname(target)] ?? 'application/octet-stream' });
  res.end(readFileSync(target));
}

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`);
  if (url.pathname === '/api/graph') {
    json(res, snapshot());
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
  if (graphText === null) {
    console.log('[board] graph not found yet — build the app once in Android Studio (any debug build)');
  } else {
    console.log(`[board] graph loaded: ${snapshot().nodes.length} nodes, fingerprint ${fingerprint}`);
  }
  console.log('[board] rebuild the app in Android Studio and the open board updates automatically');
});
