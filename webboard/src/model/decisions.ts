/**
 * TypeScript mirror of decisions.json produced by the KSP
 * processor next to graph.json: the pending decisions blocking inference,
 * rendered by the board as clickable cards. Kotlin side:
 * `kite/graph-core` Decisions.kt.
 */

export interface DecisionCandidate {
  fqn: string;
  displayName: string;
  /** Where the candidate is declared (repo-root-relative). */
  file: string;
  line: number;
  /** The exact rules line a click writes. */
  insert: string;
}

export interface PendingDecision {
  /** Stable id, e.g. `bind:com.app.data.UserRepository`. */
  id: string;
  /** 'bind' today; 'root' / 'scope' reserved. */
  kind: string;
  title: string;
  subject: string;
  consumers: string[];
  candidates: DecisionCandidate[];
}

export interface DecisionsFile {
  module: string;
  rulesFile?: string | null;
  rulesObject?: string | null;
  suggestedFile?: string | null;
  suggestedPackage?: string | null;
  pending: PendingDecision[];
}

export function emptyDecisions(): DecisionsFile {
  return { module: '', pending: [] };
}

/** Light structural validation of an untrusted decisions.json payload. */
export function parseDecisions(raw: unknown): DecisionsFile {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return emptyDecisions();
  const rec = raw as Record<string, unknown>;
  if (!Array.isArray(rec['pending'])) return emptyDecisions();
  const pending: PendingDecision[] = [];
  for (const p of rec['pending'] as unknown[]) {
    if (typeof p !== 'object' || p === null) continue;
    const d = p as Record<string, unknown>;
    if (typeof d['id'] !== 'string' || typeof d['title'] !== 'string' || !Array.isArray(d['candidates'])) continue;
    pending.push(d as unknown as PendingDecision);
  }
  return { ...(rec as unknown as DecisionsFile), pending };
}
