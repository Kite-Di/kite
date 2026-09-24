# Build errors and the rules that fix them

<https://kitedi.com/guide/errors> · <https://kitedi.com/guide/rules>

Everything the graph *is* must be provably correct at compile time. Nothing on
this page can reach a device — a graph that builds is a graph that resolves.

Every message has the same shape: a one-line verdict, indented provenance lines
with the file and line of **both** sides, then a `hint:` line.

Two checks run first — ambiguous lookups and ambiguous scope ordering poison
everything downstream, so when either fires the rest of the report is withheld
until they are fixed. Do not read a short report as a nearly-clean graph.

## GraphRules.kt

One optional file per module, any name, `GraphRules.kt` by convention.
Committed, never shipped: the annotations are `SOURCE`-retention and pulled in
`compileOnly`.

```kotlin
package com.example.feature.profile

import com.kite.di.rules.Bind
import com.kite.di.rules.Root
import com.kite.di.rules.Scoped

@Root(LegacyTracker::class)                          // plain class resolved at runtime
@Bind(PaymentGateway::class, to = StripeGateway::class)   // choose among implementations
@Scoped(SessionState::class, "activity")             // lifetime other than singleton
@Scoped(SessionCart::class, "Session", level = 10)   // custom scope needs its own level
private object GraphRules
```

Class references, not strings: a typo is an ordinary unresolved reference, rename
refactorings update it, and the file cannot rot.

`@Fresh` is the one decision that lives **on the class**, not here — "never
shared" is part of the class's own contract.

**Decisions live with the owner.** `@Bind`/`@Scoped` for a `:core:network` class
belong in `:core:network`'s rules file even when the error surfaced in `:app`.
Putting them elsewhere is itself a build error.

## Errors

### Duplicate binding

```
Duplicate binding for com.example.UserRepo:
  1) RealUserRepo (feature/profile/impl/.../RealUserRepo.kt:12)
  2) CachingUserRepo (feature/profile/impl/.../CachingUserRepo.kt:9)
  hint: keep one implementation, or choose with a @Bind rule (GraphRules.kt).
```

Two classes claim one key — almost always a project interface with two
implementations. Delete one, or decide:

```kotlin
@Bind(UserRepo::class, to = RealUserRepo::class)
```

**This is the one error to bring to the user** when the intent is not obvious
from the code. When the *consumer* should choose rather than the app, use a mark
instead of `@Bind`.

### Two implementations share one mark

```
Gateway has 2 implementations marked @Stripe (StripeGateway, BackupStripeGateway)
— a mark selects one implementation, so it cannot be shared (.../Gateway.kt:9)
```

Give them different marks, or leave one unmarked so it stays the plain binding.

### Conflict with a `Set` aggregate

```
Conflict for kotlin.collections.Set<StartupTask>: bound directly by TaskBundle
(core/.../TaskBundle.kt:7) while implementations of StartupTask also aggregate into it.
```

Pick one model: a hand-written binding for the whole set, or the plugin pattern
(implement the interface, inject `Set<I>`). Not both.

### Missing binding

```
Missing binding: no provider for com.example.Cache
  injected into: RealUserRepo, constructor param 'cache' (.../RealUserRepo.kt:12)
  hint: implement it in a project class, or let the leaf bubble up to Graph.start.
```

Write a project class for it, or let it become a graph argument that
`Graph.start` fills. A parameter with a default value is optional and never
produces this error.

If the type *is* implemented in the project, the module that implements it is
missing from the app's compile classpath — see *Unreachable dependency*.

### Dependency cycle

```
Dependency cycle detected:
  A (.../A.kt:5) → com.example.B (constructor param 'b', .../A.kt:6)
  B (.../B.kt:5) → com.example.A (constructor param 'a', .../B.kt:6)
  hint: break the cycle by injecting Provider<T> or Lazy<T> at one of the sites.
```

Make one edge deferred — `Lazy<T>` or `() -> T`. A deferred edge is not a
construction-time edge, so the cycle becomes constructible. Cross-module cycles
have no check: Gradle forbids circular project dependencies.

### Scope violation

```
Scope violation: singleton AppCache depends on activity SessionState
  singleton AppCache (core/.../AppCache.kt:8)
  depends on activity SessionState (.../SessionState.kt:4) via constructor param 'session' (.../AppCache.kt:9)
```

A longer-lived binding cannot depend on a shorter-lived one — it would outlive
and leak it. Shorten the consumer's lifetime with `@Scoped`, or make it `@Fresh`.
Enforced across module boundaries too.

### Unreachable dependency

Raised only in the application module, about a binding merged from elsewhere:

```
Unreachable dependency: RealMidApi, constructor parameter 'deep'
  provided by: :mid, merged into this application
  its type is not on this module's compile classpath …
  hint: :mid resolves it through an `implementation` dependency, which is not
  transitive — depend on that module here too, or have :mid export it with api(...).
```

`:mid` compiles fine; the app cannot see through `implementation`. Depend on that
module from the app too, or have the module in between export it with `api(...)`.

The type is not named — when a type cannot be resolved, KSP has no name to
report. Open the class the message does name and look at the parameter.

### Scope level collision

```
Scope level collision: Session and Checkout all declare level 10 — levels order
lifetimes, so each scope needs its own (built-ins: singleton = 0, activity = 1,
fragment = 2).
```

Give each custom scope its own level.

## Warnings

### Unused binding

```
Unused binding: LegacyTracker (.../LegacyTracker.kt:6) is never injected.
Add @Root(LegacyTracker::class) if it is resolved dynamically, or delete the class.
```

Only *isolated* bindings are flagged — no consumers **and** no dependencies. A
binding covered by a `@Root` is never flagged, and library modules are exempt
because downstream consumers are invisible to that compilation. Suppress on the
class with `@Suppress("kite:unused-binding")`.

During a Hilt migration this warning is the signal for every class that used to
be reached through `EntryPointAccessors`.

### Scoped captures unscoped

```
activity SessionPresenter captures unscoped Formatter for its whole lifetime …
```

Legal, but usually not intended: a scoped class holds one instance of a `@Fresh`
dependency forever while everyone else keeps getting new ones.

### Redundant scope

`@Scoped(X::class, "singleton")` warns — singleton is the default, so the line
says nothing. Delete it.

## Reading the graph directly

`build/kite/graph.json` is written per module on every debug build (dev-only,
never packaged) and holds the same data the board renders. Read it when a message
needs context — what else binds a key, what a module exports.
`build/kite/decisions.json` holds the pending decisions the board shows as cards.
