/**
 * Regenerates mock/fixtures/graph.json from the demo app's real build: the
 * application's graph merged with every library module's fragment, by the same
 * mergeSnapshots the board uses — so the mock board renders the genuine
 * multi-module graph. Run after `./gradlew :app:assembleDebug`:
 * `node --experimental-strip-types mock/build-fixture.ts`.
 */
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mergeSnapshots } from '../src/model/merge.ts';
import type { GraphSnapshot } from '../src/model/graph.ts';

const demo = fileURLToPath(new URL('../../demo/modular_app/', import.meta.url));
const read = (p: string) => JSON.parse(readFileSync(p, 'utf-8')) as GraphSnapshot;

/** Every `<module>/build/kite/graph.json` under the demo, the app's excluded. */
function fragments(dir: string): string[] {
  const found: string[] = [];
  for (const name of readdirSync(dir)) {
    if (name === 'build' || name === 'src' || name.startsWith('.')) continue;
    if (!statSync(dir + name).isDirectory()) continue;
    const path = dir + name + '/';
    const graph = path + 'build/kite/graph.json';
    if (existsSync(graph) && path !== demo + 'app/') found.push(graph);
    found.push(...fragments(path));
  }
  return found.sort();
}

const app = read(demo + 'app/build/kite/graph.json');
const merged = mergeSnapshots(app, fragments(demo).map(read));
delete (merged as { runtime?: unknown }).runtime;

const out = fileURLToPath(new URL('./fixtures/graph.json', import.meta.url));
writeFileSync(out, JSON.stringify(merged, null, 2) + '\n');

const modules = new Set(merged.nodes.map((n) => n.providedBy?.gradleModule ?? (n.kind === 'external' ? 'platform' : '?')));
console.log(`merged fixture: ${merged.nodes.length} nodes, ${merged.edges.length} edges`);
console.log('modules present:', [...modules].sort().join(', '));
