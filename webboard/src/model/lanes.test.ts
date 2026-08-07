import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { deriveLanes, PLATFORM_LANE } from './lanes';
import type { GraphNode, GraphSnapshot } from './graph';

function node(id: string, file: string | null, module = ':app', kind: GraphNode['kind'] = 'injectable'): GraphNode {
  return {
    id,
    type: id,
    displayName: id.split('.').pop()!,
    kind,
    boundTo: [],
    ...(file ? { providedBy: { declaration: id, gradleModule: module, file, line: 1 } } : {}),
  };
}

describe('deriveLanes', () => {
  it('single module: lanes come from declaration directories relative to the common root', () => {
    const nodes = [
      node('a.Repo', 'app/src/main/java/com/x/data/Repo.kt'),
      node('a.Presenter', 'app/src/main/java/com/x/ui/Presenter.kt'),
      node('a.Main', 'app/src/main/java/com/x/Main.kt'),
      node('android.content.Context', null, ':app', 'external'),
    ];
    const lanes = deriveLanes(nodes, []);
    expect(lanes.get('a.Repo')).toBe('data');
    expect(lanes.get('a.Presenter')).toBe('ui');
    expect(lanes.get('a.Main')).toBe('app');
    expect(lanes.get('android.content.Context')).toBe(PLATFORM_LANE);
  });

  it('multi module: lanes are the declaring Gradle modules', () => {
    const nodes = [
      node('a.Repo', 'core/src/main/java/com/x/Repo.kt', ':core'),
      node('a.Screen', 'app/src/main/java/com/x/Screen.kt', ':app'),
    ];
    const lanes = deriveLanes(nodes, []);
    expect(lanes.get('a.Repo')).toBe(':core');
    expect(lanes.get('a.Screen')).toBe(':app');
  });

  it('undeclared nodes (set aggregates) inherit the majority lane of their neighbors', () => {
    const nodes = [
      node('a.TaskA', 'app/src/main/java/com/x/di/A.kt'),
      node('a.TaskB', 'app/src/main/java/com/x/di/B.kt'),
      node('a.Ui', 'app/src/main/java/com/x/ui/Ui.kt'),
      { ...node('kotlin.collections.Set<a.Task>', null), kind: 'set' as const },
    ];
    const edges = [
      { id: 'e1', from: 'kotlin.collections.Set<a.Task>', to: 'a.TaskA', siteKind: 'setContribution' as const, deferred: 'none' as const },
      { id: 'e2', from: 'kotlin.collections.Set<a.Task>', to: 'a.TaskB', siteKind: 'setContribution' as const, deferred: 'none' as const },
      { id: 'e3', from: 'a.Ui', to: 'kotlin.collections.Set<a.Task>', siteKind: 'constructorParam' as const, deferred: 'none' as const },
    ];
    expect(deriveLanes(nodes, edges).get('kotlin.collections.Set<a.Task>')).toBe('di');
  });

  it('real demo graph is multi-module: every node gets a lane, :app and :core both present', () => {
    const fixture = fileURLToPath(new URL('../../mock/fixtures/graph.json', import.meta.url));
    const snapshot = JSON.parse(readFileSync(fixture, 'utf-8')) as GraphSnapshot;
    const lanes = deriveLanes(snapshot.nodes, snapshot.edges);
    for (const n of snapshot.nodes) {
      expect(lanes.get(n.id), `lane for ${n.id}`).toBeTruthy();
    }
    // the fixture is the real merged graph — lanes are Gradle modules, not folders
    const distinct = new Set(lanes.values());
    expect(distinct).toContain(':app');
    expect(distinct).toContain(':core');
  });
});
