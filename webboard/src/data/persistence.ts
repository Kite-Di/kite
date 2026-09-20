/**
 * Local persistence: last snapshot per appId, camera
 * pose, pinned node positions and the last seen buildFingerprint — so the
 * graph renders instantly on page load, before (or without) a connection.
 *
 * Backed by localStorage via a small typed wrapper rather than IndexedDB:
 * snapshots within the 2 MB budget fit comfortably, and it keeps this
 * dependency-free and synchronous.
 */

import type { GraphSnapshot } from '../model/graph';

const PREFIX = 'webboard';

export interface CameraPose {
  x: number;
  y: number;
  scale: number;
}

export type PinnedPositions = Record<string, { x: number; y: number }>;

export interface PersistedSession {
  snapshot: GraphSnapshot;
  buildFingerprint: string | null;
  savedAt: number;
}

function key(appId: string, part: string): string {
  return `${PREFIX}:${appId}:${part}`;
}

function readJson<T>(k: string): T | null {
  try {
    const raw = localStorage.getItem(k);
    return raw === null ? null : (JSON.parse(raw) as T);
  } catch {
    return null;
  }
}

function writeJson(k: string, value: unknown): void {
  try {
    localStorage.setItem(k, JSON.stringify(value));
  } catch {
    // Quota exceeded / private mode — persistence is best-effort.
  }
}

export const persistence = {
  lastAppId(): string | null {
    try {
      return localStorage.getItem(`${PREFIX}:lastAppId`);
    } catch {
      return null;
    }
  },

  saveSession(appId: string, snapshot: GraphSnapshot, buildFingerprint: string | null): void {
    try {
      localStorage.setItem(`${PREFIX}:lastAppId`, appId);
    } catch {
      /* best-effort */
    }
    writeJson(key(appId, 'session'), {
      snapshot,
      buildFingerprint,
      savedAt: Date.now(),
    } satisfies PersistedSession);
  },

  loadSession(appId: string): PersistedSession | null {
    return readJson<PersistedSession>(key(appId, 'session'));
  },

  saveCamera(appId: string, pose: CameraPose): void {
    writeJson(key(appId, 'camera'), pose);
  },

  loadCamera(appId: string): CameraPose | null {
    const pose = readJson<CameraPose>(key(appId, 'camera'));
    if (!pose || !Number.isFinite(pose.x) || !Number.isFinite(pose.y) || !(pose.scale > 0)) return null;
    return pose;
  },

  savePins(appId: string, pins: PinnedPositions): void {
    writeJson(key(appId, 'pins'), pins);
  },

  loadPins(appId: string): PinnedPositions {
    return readJson<PinnedPositions>(key(appId, 'pins')) ?? {};
  },
};
