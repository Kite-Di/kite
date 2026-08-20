/**
 * Schema-sync test: parses the Kotlin golden file produced by
 * `kite/graph-core` GraphJsonGoldenTest through the TS types, plus the
 * real exported app graph used by the mock server. Drift on either side of
 * the Kotlin ⇄ TypeScript mirror fails this suite.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parseSnapshot, SnapshotParseError, withDerivedEdges, type GraphSnapshot } from './graph';

// webboard/src/model/ → repo root is ../../..
const GOLDEN_URL = new URL(
  '../../../kite/graph-core/src/test/resources/golden/snapshot-v2.json',
  import.meta.url,
);
const FIXTURE_URL = new URL('../../mock/fixtures/graph.json', import.meta.url);

function load(url: URL): GraphSnapshot {
  return parseSnapshot(JSON.parse(readFileSync(fileURLToPath(url), 'utf-8')));
}

describe('graph-core golden file (snapshot-v2.json)', () => {
  const snap = load(GOLDEN_URL);

  it('parses through the TS types with expected counts', () => {
    expect(snap.schemaVersion).toBe(2);
    expect(snap.appId).toBe('com.kite.demo');
    expect(snap.variant).toBe('debug');
    expect(snap.scopes).toHaveLength(3);
    expect(snap.nodes).toHaveLength(2);
    expect(snap.edges).toHaveLength(1);
  });

  it('exposes node fields per schema', () => {
    const repo = snap.nodes.find((n) => n.id === 'com.example.data.UserRepo');
    expect(repo).toBeDefined();
    expect(repo!.kind).toBe('injectable');
    expect(repo!.scope).toBe('Singleton');
    expect(repo!.boundTo).toEqual(['com.example.data.Repo']);
    expect(repo!.providedBy?.gradleModule).toBe(':app');
    expect(repo!.providedBy?.line).toBe(8);
    expect(repo!.inferredBy).toBe('implementation'); // schema v2

    const ok = snap.nodes.find((n) => n.id === 'auth@okhttp3.OkHttpClient');
    expect(ok).toBeDefined();
    expect(ok!.qualifier).toBe('auth');
    expect(ok!.kind).toBe('provides');
    expect(ok!.scope ?? null).toBeNull(); // unscoped
  });

  it('exposes edge fields per schema', () => {
    const edge = snap.edges[0]!;
    expect(edge.id).toBe('com.example.ui.MainPresenter -> com.example.data.UserRepo # 0');
    expect(edge.from).toBe('com.example.ui.MainPresenter');
    expect(edge.to).toBe('com.example.data.UserRepo');
    expect(edge.siteKind).toBe('constructorParam');
    expect(edge.paramName).toBe('repo');
    expect(edge.deferred).toBe('none');
    expect(edge.site?.line).toBe(19);
  });

  it('exposes the runtime section', () => {
    expect(snap.runtime).toBeDefined();
    expect(snap.runtime!.openScopes).toHaveLength(2);
    expect(snap.runtime!.openScopes[1]!.parent).toBe('app');
    const inst = snap.runtime!.instances[0]!;
    expect(inst.nodeId).toBe('com.example.data.UserRepo');
    expect(inst.creationMicros).toBe(412);
  });

  it('exposes viewModelUsages', () => {
    const usage = snap.runtime!.viewModelUsages?.[0];
    expect(usage).toBeDefined();
    expect(usage!.nodeId).toBe('com.example.ui.GreetingViewModel');
    expect(usage!.ownerId).toBe('com.example.ui.MainActivity');
    expect(usage!.ownerDisplay).toBe('MainActivity');
  });
});

describe('real exported app graph (mock/fixtures/graph.json)', () => {
  const snap = load(FIXTURE_URL);

  it('parses through the TS types with expected counts', () => {
    expect(snap.schemaVersion).toBe(2);
    expect(snap.appId).toBe('com.kite.demo');
    expect(snap.nodes).toHaveLength(26);
    expect(snap.edges).toHaveLength(27);
    // Build artifact has no runtime section.
    expect(snap.runtime ?? null).toBeNull();
  });

  it('is the real merged multi-module graph: :app and :core both declare nodes', () => {
    const modules = new Set(snap.nodes.map((n) => n.providedBy?.gradleModule).filter(Boolean));
    expect(modules).toContain(':app');
    expect(modules).toContain(':core');
  });

  it('ViewModels are the entry-point node kind', () => {
    // Activities/Fragments resolve at runtime and are invisible to inference —
    // the entry points in the graph are the ViewModels, reached via adapters.
    const vm = snap.nodes.find((n) => n.id === 'com.kite.demo.ui.CounterViewModel');
    expect(vm).toBeDefined();
    expect(vm!.kind).toBe('entryPoint');
  });

  it('covers the inferred graph node kinds and qualifier/deferred variants', () => {
    const kinds = new Set(snap.nodes.map((n) => n.kind));
    // the inferred paradigm's five kinds — no @Provides/@IntoMap concepts exist
    expect(kinds).toContain('injectable');
    expect(kinds).toContain('boundInterface');
    expect(kinds).toContain('entryPoint');
    expect(kinds).toContain('external');
    expect(kinds).toContain('set'); // Set<StartupTask> / Set<PayloadParser> multibindings

    const apiKey = snap.nodes.find((n) => n.id === 'apiKey@kotlin.String');
    expect(apiKey?.qualifier).toBe('apiKey');

    // NetworkUserRepository(analytics: Lazy<Analytics>) — a deferred edge
    const lazyEdge = snap.edges.find((e) => e.deferred === 'lazy' && e.paramName === 'analytics');
    expect(lazyEdge).toBeDefined();
    expect(lazyEdge!.from).toBe('com.kite.demo.data.NetworkUserRepository');

    // no field-injection edges: `by injected()` sites live in function bodies,
    // which static inference cannot see.
    expect(snap.edges.every((e) => e.siteKind !== 'field')).toBe(true);
  });
});

describe('withDerivedEdges', () => {
  it('derives a binds edge per boundTo entry (interface satellite → implementation)', () => {
    const snap = withDerivedEdges(load(FIXTURE_URL));
    const impl = 'com.kite.demo.data.NetworkUserRepository';
    const iface = 'com.kite.demo.data.UserRepository';
    const edge = snap.edges.find((e) => e.id === `${iface} -> ${impl} # binds`);
    expect(edge).toBeDefined();
    expect(edge!.siteKind).toBe('binds');
    expect(edge!.from).toBe(iface);
    expect(edge!.to).toBe(impl);
  });

  it('derives a screen node + viewModel edge per runtime usage', () => {
    const base = load(FIXTURE_URL);
    const vm = 'com.kite.demo.ui.GreetingViewModel';
    const owner = 'com.kite.demo.ui.MainActivity';
    const snap = withDerivedEdges({
      ...base,
      runtime: {
        openScopes: [],
        instances: [],
        viewModelUsages: [
          { nodeId: vm, ownerId: owner, ownerDisplay: 'MainActivity' },
          // usage from another build (unknown ViewModel) is skipped, not invented
          { nodeId: 'com.example.GhostViewModel', ownerId: owner, ownerDisplay: 'MainActivity' },
        ],
      },
    });
    const ownerNode = snap.nodes.find((n) => n.id === owner);
    expect(ownerNode).toBeDefined();
    expect(ownerNode!.kind).toBe('entryPoint');
    expect(ownerNode!.displayName).toBe('MainActivity');
    const edge = snap.edges.find((e) => e.id === `${owner} -> ${vm} # vm`);
    expect(edge).toBeDefined();
    expect(edge!.siteKind).toBe('viewModel');
    expect(snap.nodes.some((n) => n.id === 'com.example.GhostViewModel')).toBe(false);
  });

  it('skips boundTo targets that are not in the snapshot', () => {
    const base = load(FIXTURE_URL);
    const snap = withDerivedEdges({
      ...base,
      nodes: base.nodes.map((n) =>
        n.id === 'com.kite.demo.data.NetworkUserRepository'
          ? { ...n, boundTo: [...n.boundTo, 'com.example.NotInGraph'] }
          : n,
      ),
    });
    expect(snap.edges.some((e) => e.from === 'com.example.NotInGraph')).toBe(false);
  });

  it('is idempotent — enriching an enriched snapshot adds nothing', () => {
    const once = withDerivedEdges(load(FIXTURE_URL));
    const twice = withDerivedEdges(once);
    expect(twice).toBe(once); // unchanged input returns the same object
    expect(twice.edges).toHaveLength(once.edges.length);
    expect(twice.nodes).toHaveLength(once.nodes.length);
  });
});

describe('parseSnapshot validation', () => {
  it('rejects non-objects and missing fields', () => {
    expect(() => parseSnapshot(null)).toThrow(SnapshotParseError);
    expect(() => parseSnapshot([])).toThrow(SnapshotParseError);
    expect(() => parseSnapshot({})).toThrow(SnapshotParseError);
  });

  it('rejects unknown schema versions', () => {
    const snap = load(GOLDEN_URL);
    expect(() => parseSnapshot({ ...snap, schemaVersion: 99 })).toThrow(/unsupported schemaVersion 99/);
  });
});
