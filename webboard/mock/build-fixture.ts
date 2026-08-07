/**
 * Regenerates mock/fixtures/graph.json from the real built module fragments
 * (app + core), merged with the production mergeSnapshots — so the dev/mock
 * board renders the genuine multi-module graph (module containers, not folders).
 * Run after a debug build: `node --experimental-strip-types mock/build-fixture.ts`.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mergeSnapshots } from '../src/model/merge.ts';
import type { GraphSnapshot } from '../src/model/graph.ts';

const repo = fileURLToPath(new URL('../../', import.meta.url));
const read = (p: string) => JSON.parse(readFileSync(repo + p, 'utf-8')) as GraphSnapshot;

const app = read('app/build/kite/graph.json');
const core = read('core/build/kite/graph.json');
const merged = mergeSnapshots(app, [core]);
delete (merged as { runtime?: unknown }).runtime;

const out = fileURLToPath(new URL('./fixtures/graph.json', import.meta.url));
writeFileSync(out, JSON.stringify(merged, null, 2) + '\n');

const modules = new Set(merged.nodes.map((n) => n.providedBy?.gradleModule ?? (n.kind === 'external' ? 'platform' : '?')));
console.log(`merged fixture: ${merged.nodes.length} nodes, ${merged.edges.length} edges`);
console.log('modules present:', [...modules].sort().join(', '));
