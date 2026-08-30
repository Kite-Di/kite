/**
 * Runtime bridge to a device.
 *
 * The graph lives here, on the development machine; what only the phone knows is
 * what actually happened at runtime — which instances were constructed, which
 * scopes are open, which screen resolved which ViewModel. This module fetches
 * that over adb so the board shows both in one place, and so nobody has to type
 * `adb forward` to get it.
 *
 * Nothing here is required: no adb, no device, or an app built without the
 * inspector simply means no runtime badges, and the static graph still renders.
 */

import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { WebSocket } from 'ws';
import type { RuntimeState } from '../src/model/graph.ts';

const run = promisify(execFile);

/** Ports the inspector may have taken: its default, plus the five it falls back to. */
const DEVICE_PORT_FIRST = 8394;
const DEVICE_PORT_LAST = DEVICE_PORT_FIRST + 5;
const POLL_MS = 3000;
const PROBE_TIMEOUT_MS = 1500;

export interface DeviceBridgeCallbacks {
  /** A runtime event from the device, already in the board's wire format. */
  onEvent: (message: Record<string, unknown>) => void;
  /** Connected/disconnected — the caller re-sends a snapshot so badges appear at once. */
  onStatus: (connected: boolean, serial: string | null) => void;
  /** The appId the host graph belongs to; a device running something else is ignored. */
  appId: () => string;
}

/** Locates adb the way Android Studio does: env first, then the default SDK path. */
function findAdb(): string | null {
  const fromPath = process.env['ADB'];
  if (fromPath && existsSync(fromPath)) return fromPath;
  for (const home of [process.env['ANDROID_HOME'], process.env['ANDROID_SDK_ROOT']]) {
    if (!home) continue;
    const candidate = join(home, 'platform-tools', 'adb');
    if (existsSync(candidate)) return candidate;
  }
  const mac = join(homedir(), 'Library', 'Android', 'sdk', 'platform-tools', 'adb');
  if (existsSync(mac)) return mac;
  const linux = join(homedir(), 'Android', 'Sdk', 'platform-tools', 'adb');
  if (existsSync(linux)) return linux;
  return null;
}

export class DeviceBridge {
  private readonly adb = findAdb();
  private timer: ReturnType<typeof setInterval> | null = null;
  private socket: WebSocket | null = null;
  private serial: string | null = null;
  private hostPort: number | null = null;
  private connecting = false;

  /** Latest runtime state from the device, attached to every snapshot the board serves. */
  runtime: RuntimeState | null = null;

  // Not a parameter property: the board server runs through Node's type stripping,
  // which refuses them.
  private readonly cb: DeviceBridgeCallbacks;

  constructor(cb: DeviceBridgeCallbacks) {
    this.cb = cb;
  }

  start(): void {
    if (!this.adb) {
      console.log('[board] adb not found — runtime badges need it (set ANDROID_HOME or ADB)');
      return;
    }
    void this.tick();
    this.timer = setInterval(() => void this.tick(), POLL_MS);
  }

  /**
   * Asks the device for a fresh runtime snapshot. Called when a board connects: the
   * cached state is as old as the last device snapshot, and a developer who opens
   * the board an hour into a session should not see an hour-old ledger.
   */
  requestResync(): void {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: 'resync' }));
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    void this.disconnect();
  }

  private async tick(): Promise<void> {
    if (this.socket || this.connecting) return;
    const serials = await this.devices();
    for (const serial of serials) {
      if (await this.tryDevice(serial)) return;
    }
  }

  private async devices(): Promise<string[]> {
    try {
      const { stdout } = await run(this.adb!, ['devices']);
      return stdout
        .split('\n')
        .slice(1)
        .map((line) => line.trim().split(/\s+/))
        .filter((parts) => parts.length >= 2 && parts[1] === 'device')
        .map((parts) => parts[0]!);
    } catch {
      return [];
    }
  }

  /**
   * Walks the ports the inspector might be on. `adb forward tcp:0` lets adb pick a
   * free host port and print it, so the board never collides with anything — least
   * of all with itself, which is what a fixed 8394 forward would do.
   */
  private async tryDevice(serial: string): Promise<boolean> {
    this.connecting = true;
    try {
      for (let devicePort = DEVICE_PORT_FIRST; devicePort <= DEVICE_PORT_LAST; devicePort++) {
        let hostPort: number;
        try {
          const { stdout } = await run(this.adb!, ['-s', serial, 'forward', 'tcp:0', `tcp:${devicePort}`]);
          hostPort = Number(stdout.trim());
          if (!Number.isFinite(hostPort) || hostPort <= 0) continue;
        } catch {
          continue;
        }
        const meta = await this.probe(hostPort);
        if (meta && meta.appId === this.cb.appId()) {
          this.serial = serial;
          this.hostPort = hostPort;
          this.connect(hostPort, serial, meta.appId);
          return true;
        }
        await this.removeForward(serial, hostPort);
      }
      return false;
    } finally {
      this.connecting = false;
    }
  }

  private async probe(hostPort: number): Promise<{ appId: string } | null> {
    try {
      const response = await fetch(`http://127.0.0.1:${hostPort}/api/meta`, {
        signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
      });
      if (!response.ok) return null;
      const meta = (await response.json()) as { appId?: unknown };
      return typeof meta.appId === 'string' ? { appId: meta.appId } : null;
    } catch {
      return null;
    }
  }

  private connect(hostPort: number, serial: string, appId: string): void {
    const socket = new WebSocket(`ws://127.0.0.1:${hostPort}/api/live`);
    this.socket = socket;

    socket.on('open', () => {
      console.log(`[board] runtime connected: ${appId} on ${serial} (adb tcp:${hostPort})`);
      this.cb.onStatus(true, serial);
    });

    socket.on('message', (raw: Buffer) => {
      let message: Record<string, unknown>;
      try {
        message = JSON.parse(raw.toString()) as Record<string, unknown>;
      } catch {
        return;
      }
      const type = message['type'];
      // A device reports runtime only — structure is ours, read from the build
      // output. `runtime.state` is the full ledger (on connect and on resync);
      // `runtime.*` events are the deltas that follow.
      if (type === 'runtime.state') {
        this.runtime = (message['runtime'] as RuntimeState | undefined) ?? null;
        this.cb.onStatus(true, serial);
        return;
      }
      if (typeof type === 'string' && type.startsWith('runtime.')) this.cb.onEvent(message);
    });

    socket.on('close', () => void this.onSocketClosed(serial));
    socket.on('error', () => void this.onSocketClosed(serial));
  }

  private async onSocketClosed(serial: string): Promise<void> {
    if (!this.socket) return;
    console.log(`[board] runtime disconnected (${serial}) — static graph only`);
    await this.disconnect();
    this.cb.onStatus(false, null);
  }

  private async disconnect(): Promise<void> {
    const socket = this.socket;
    this.socket = null;
    this.runtime = null;
    socket?.removeAllListeners();
    socket?.terminate();
    if (this.serial && this.hostPort !== null) await this.removeForward(this.serial, this.hostPort);
    this.serial = null;
    this.hostPort = null;
  }

  private async removeForward(serial: string, hostPort: number): Promise<void> {
    try {
      await run(this.adb!, ['-s', serial, 'forward', '--remove', `tcp:${hostPort}`]);
    } catch {
      // the device may already be gone; nothing to clean up
    }
  }
}
