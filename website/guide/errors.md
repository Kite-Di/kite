# Build errors

Everything the graph *is* must be provably correct at compile time — that is the
half of the Dagger contract Kite keeps even though the graph is inferred rather
than declared. Nothing on this page can reach a device.

Every message has the same shape: a one-line verdict, indented provenance lines
with the file and line of **both** sides, then a `hint:` line.

Two checks run first — ambiguous lookups and ambiguous scope ordering poison
everything downstream, so if either fires you get only those, and the rest of the
report appears once they are fixed.

## Duplicate binding

Two classes claim the same key. Almost always: a project interface with two
implementations.

```
Duplicate binding for com.example.UserRepo:
  1) RealUserRepo (feature/profile/impl/.../RealUserRepo.kt:12)
  2) CachingUserRepo (feature/profile/impl/.../CachingUserRepo.kt:9)
  hint: keep one implementation, or choose with a @Bind rule (GraphRules.kt).
```

**Fix:** delete one, or decide. The decision goes in the owning module's
[`GraphRules.kt`](/guide/rules):

```kotlin
@Bind(UserRepo::class, to = RealUserRepo::class)
```

[The board](/guide/board) shows this one as a decision card and writes the line
for you.

## Two implementations share one mark

```
Gateway has 2 implementations marked @Stripe (StripeGateway, BackupStripeGateway)
— a mark selects one implementation, so it cannot be shared (.../Gateway.kt:9)
```

**Fix:** give them different marks, or leave one unmarked so it stays the plain
binding. See [marks](/guide/patterns#choosing-between-implementations-marks).

## Conflict with a `Set` aggregate

A class binds a key directly while implementations of the element type also
aggregate into it:

```
Conflict for kotlin.collections.Set<StartupTask>: bound directly by TaskBundle
(core/.../TaskBundle.kt:7) while implementations of StartupTask also aggregate into it.
```

**Fix:** pick one model — either a hand-written binding for the whole set, or the
[plugin pattern](/guide/patterns#the-plugin-pattern-set-i).

## Missing binding

Something needs a type nothing provides, and it cannot become a graph argument:

```
Missing binding: no provider for com.example.Cache
  injected into: RealUserRepo, constructor param 'cache' (.../RealUserRepo.kt:12)
  hint: implement it in a project class, or let the leaf bubble up to Graph.start.
```

**Fix:** write a project class for it, or let it become a
[graph argument](/guide/inference#graph-arguments) that `Graph.start` fills. A
parameter with a [default value](/guide/patterns#optional-dependencies-default-values)
is optional and never produces this error.

## Dependency cycle

```
Dependency cycle detected:
  A (.../A.kt:5) → com.example.B (constructor param 'b', .../A.kt:6)
  B (.../B.kt:5) → com.example.A (constructor param 'a', .../B.kt:6)
  hint: break the cycle by injecting Provider<T> or Lazy<T> at one of the sites.
```

**Fix:** make one edge deferred — `Lazy<T>` or `() -> T`. A deferred edge is not a
construction-time edge, so the cycle becomes constructible. See
[patterns](/guide/patterns#defer-or-re-create-lazy-t-and-t).

Cross-module cycles have no check because they cannot be expressed: Gradle
forbids circular project dependencies.

## Scope violation

A longer-lived binding depends on a shorter-lived one, which would outlive and
leak it:

```
Scope violation: singleton AppCache depends on activity SessionState
  singleton AppCache (core/.../AppCache.kt:8)
  depends on activity SessionState (.../SessionState.kt:4) via constructor param 'session' (.../AppCache.kt:9)
  hint: a longer-lived binding cannot depend on a shorter-lived one — give AppCache an
  equal-or-shorter scope (@Scoped in GraphRules.kt), or mark it @Fresh (a new instance
  per consumer, no cache).
```

**Fix:** shorten the consumer's lifetime, or make it `@Fresh`. This check holds
across module boundaries too. See [lifetimes](/guide/lifetimes#direction-is-enforced).

## Unreachable dependency

Raised only in the application module, about a binding it is merging from another
module:

```
Unreachable dependency: RealMidApi, constructor parameter 'deep'
  provided by: :mid, merged into this application
  its type is not on this module's compile classpath, so the module that
  provides it is missing from the merged graph and resolution would fail at runtime.
  hint: :mid resolves it through an `implementation` dependency, which is not
  transitive — depend on that module here too, or have :mid export it with api(...).
```

`:mid` compiles fine: it can see the module it needs. Your app cannot, because
Gradle's `implementation` is not transitive — a module pulled in that way never
reaches the app's compile classpath, so its bindings are absent from the merged
graph.

**Fix:** depend on that module from the app too, or have the module in between
export it with `api(...)` instead of `implementation(...)`.

The type is not named in the message: when a type cannot be resolved, KSP has no
name to report. Open the class it names and look at the parameter.

## Scope level collision

```
Scope level collision: Session and Checkout all declare level 10 — levels order
lifetimes, so each scope needs its own (built-ins: singleton = 0, activity = 1,
fragment = 2).
```

**Fix:** give each custom scope its own level. See
[custom scopes](/guide/lifetimes#custom-scopes).

## Warnings

### Unused binding

```
Unused binding: LegacyTracker (.../LegacyTracker.kt:6) is never injected.
Add @Root(LegacyTracker::class) if it is resolved dynamically, or delete the class.
```

Only *isolated* bindings are flagged — no consumers **and** no dependencies.
Anything with dependencies is usually a root resolved at runtime through
`by injected()` or `Kite.get`, which the processor cannot see, so flagging it
would be noise. A binding included by a `@Root` rule is never flagged: being
resolved at runtime is its reason to exist.

In a library module, implementations are the module's exported surface and
downstream consumers are invisible to that compilation, so they are not flagged
either.

Suppress it on a class with `@Suppress("kite:unused-binding")`.

### Scoped captures unscoped

```
activity SessionPresenter captures unscoped Formatter for its whole lifetime
(.../SessionPresenter.kt:9), while other consumers get fresh instances — if
sharing is intended, remove its @Fresh / "none" decision (singleton is the default).
```

Legal, but usually not what you meant: a scoped class holds one instance of a
`@Fresh` dependency forever while everyone else keeps getting new ones.

### Redundant scope

`@Scoped(X::class, "singleton")` warns, because singleton is the default and the
line says nothing.
