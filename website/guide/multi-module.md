# Multi-module apps

Apply the plugin in every module that has graph classes — and only those.

The repository's demo app, under `demo/modular_app`, is cut like a production
codebase: an `:app` shell,
shared infrastructure under `:core:network`, `:core:analytics` and
`:core:designsystem`, and clean-architecture features split into
`:feature:profile:api`/`:impl` and `:feature:orders:api`/`:impl`. The design
system and the pure-contract `:api` modules carry no plugin at all — nothing in
them is injectable. The app module's generated `Graph.start` covers everything.

![The board with one container per module](/img/board-modules.png)

## What crosses a module boundary

### Interface implementations travel free

Even across an api/impl split. `:feature:profile:impl`'s
`NetworkUserRepository : UserRepository` binds to the interface declared in
`:feature:profile:api` and is injectable from `:app` with zero ceremony. A module
exports its implementations and everything their constructors pull in.

### Exporting a *plain* class is a decision

A plain class consumed only by other modules is not in its own module's inferred
graph — nothing in that module constructs it. Add `@Root(Foo::class)` to the
**owning** module's [`GraphRules.kt`](/guide/rules). It is the same rule as for
`by injected()`: a root declares consumers the compiler cannot see.

Put the class behind an interface instead and the question disappears — the
interface binding exports it on the spot. That is why the demo's `:core:network`
has no rules file at all, and `:core:analytics`'s holds exactly one line: the
`@Bind` choosing between its two `Analytics` implementations.

### Decisions live with the owner

`@Scoped`/`@Bind` for a `:core:network` class belong in `:core:network`'s rules
file. The build tells you so if you put them elsewhere, and an ambiguity you hit
from `:app` sends you to the owning module's file.

### Graph arguments unite

An `apiKey: String` leaf in any module becomes the single
`Graph.start(apiKey = …)` parameter. The name is the identity, so the same
argument declared in three modules is filled once.

### One board

All modules render on one canvas. Each is a labeled container — a translucent
backdrop around its nodes — in its own accent colour. Toggle containers with the
**Modules** button or `m`.

## What is still checked across modules

[Scope violations](/guide/errors#scope-violation) remain build errors across
module boundaries. Cross-module dependency *cycles* cannot even be expressed:
Gradle forbids circular project dependencies, so the check has nothing to do.

## A plugin architecture {#a-plugin-architecture}

Because `Set<I>` collects across modules, a feature can register itself by
implementing a contract — the application names no feature, and adding one is a
line in its dependency list.

```
core/plugin        interface StartupTask, DeepLinkHandler, SettingsEntry
                   OrderedStartup(tasks: Set<StartupTask>) and the other aggregators

feature/orders     impl: the repository, plus SyncOrdersTask, OrdersDeepLink, OrdersSettings
feature/profile    impl: …
feature/chat       impl: …

app                asks :core:plugin for Startup and Router — and nothing else
```

The aggregators sit beside the contracts, above the features that contribute to
them: `:feature:orders:impl` depends on `:core:plugin`, never the other way
round. That direction is fine — the application composes the sets, since only its
compilation sees every contributor, and the aggregator receives one complete set.

Infrastructure contributes on the same terms: `:core:network` adding a
`StartupTask` is indistinguishable from a feature doing it.

The repository's `demo/plugin_app` is exactly this, with three features and three
extension points.
