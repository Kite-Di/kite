/**
 * Ownership lane derivation — "who owns this node".
 *
 * The lane label drives the card's accent color and seeds row ordering in the
 * vertical layout (same-lane nodes start adjacent). Rules:
 *
 *  1. Multi-module graph → lane = the Gradle module that declares the binding
 *     (`providedBy.gradleModule`), so users see which module a dependency
 *     comes from at a glance.
 *  2. Single module → lane = the declaration's directory relative to the
 *     common source root (`data`, `di`, `ui`; the root itself → "app").
 *  3. Environment-provided nodes (Application/Context) → "platform".
 *  4. Nodes without a declaration (Set<T> aggregates) inherit the majority
 *     lane of their direct neighbors.
 */

import type { GraphEdge, GraphNode } from './graph';

export const PLATFORM_LANE = 'platform';
const ROOT_LANE = 'app';

export function deriveLanes(nodes: GraphNode[], edges: GraphEdge[]): Map<string, string> {
  const lanes = new Map<string, string>();

  const modules = new Set(
    nodes.map((n) => n.providedBy?.gradleModule).filter((m): m is string => !!m),
  );
  const multiModule = modules.size > 1;

  const dirOf = (file: string): string[] => file.split('/').slice(0, -1);
  const declared = nodes.filter((n) => n.providedBy);
  const commonDir = commonPrefix(declared.map((n) => dirOf(n.providedBy!.file)));

  for (const n of nodes) {
    if (n.kind === 'external') {
      lanes.set(n.id, PLATFORM_LANE);
    } else if (n.providedBy) {
      if (multiModule) {
        lanes.set(n.id, n.providedBy.gradleModule);
      } else {
        const rest = dirOf(n.providedBy.file).slice(commonDir.length);
        lanes.set(n.id, rest[0] ?? ROOT_LANE);
      }
    }
  }

  // Undeclared nodes (set aggregates): majority lane among direct neighbors.
  for (const n of nodes) {
    if (lanes.has(n.id)) continue;
    const votes = new Map<string, number>();
    for (const e of edges) {
      const other = e.from === n.id ? e.to : e.to === n.id ? e.from : null;
      const lane = other !== null ? lanes.get(other) : undefined;
      if (lane && lane !== PLATFORM_LANE) votes.set(lane, (votes.get(lane) ?? 0) + 1);
    }
    const winner = [...votes.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0];
    lanes.set(n.id, winner?.[0] ?? ROOT_LANE);
  }
  return lanes;
}

function commonPrefix(paths: string[][]): string[] {
  if (paths.length === 0) return [];
  let prefix = paths[0]!;
  for (const p of paths) {
    let i = 0;
    while (i < prefix.length && i < p.length && prefix[i] === p[i]) i++;
    prefix = prefix.slice(0, i);
  }
  return prefix;
}
