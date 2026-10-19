# Build errors

Everything the graph *is* must be provably correct at compile time — that is the
half of the Dagger contract Kite keeps even though the graph is inferred rather
than declared. Nothing on this page can reach a device; the three things that
still can are at the [end](#what-can-still-fail-at-runtime).

Every message has the same shape: a one-line verdict, indented provenance lines
with the file and line of **both** sides, then a `hint:` line. Paths below are
shortened with `…`.

Ambiguities and scope collisions are reported first — they poison everything
downstream — and the rest of the report appears once they are fixed.

## Choosing an implementation

### Two implementations of one interface

The common one. Two classes implement an interface, and something takes it as a
single parameter:

```
UserRepo has 2 implementations:
  1) RealUserRepo (…/RealUserRepo.kt:12)
  2) CachingUserRepo (…/CachingUserRepo.kt:9)
consumed as a single UserRepo by ProfileViewModel (…/ProfileViewModel.kt:8)
  hint: add to GraphRules.kt:  @Bind(UserRepo::class, to = RealUserRepo::class)
  (the dependency board shows this as a clickable decision card)
```

**Fix:** paste the line from the hint into the module's
[`GraphRules.kt`](/guide/rules), or click the card on [the board](/guide/board).
If the consumer should choose instead of the app, use
[marks](/guide/patterns#choosing-between-implementations-marks). Two `Supplies<T>`
classes for one type read the same, with "is supplied by 2 classes".

An interface nothing consumes as a single value is not an error: its
implementations stay in the graph under their own class, and `Set<UserRepo>`
collects them all.

### The ambiguity is in another module

The implementations live in a module you depend on, which left the choice open:

```
Gateway has multiple implementations in :payments, consumed as a single Gateway by Checkout (…/Checkout.kt:4)
  hint: add a @Bind(Gateway::class, to = …::class) decision to :payments's GraphRules.kt — the choice
  belongs to the module that owns the implementations.
```

### Two implementations share one mark

```
Gateway has 2 implementations marked @Stripe (StripeGateway, BackupStripeGateway) — a mark
selects one implementation, so it cannot be shared (…/Gateway.kt:9).
```

**Fix:** give them different marks, or leave one unmarked so it stays the plain
binding.

### Already bound in another module

A module implements an interface that a module it depends on already binds:

```
Analytics is implemented by DebugAnalytics (…/DebugAnalytics.kt:3) but is already bound in :core:analytics.
  hint: keep one implementation per graph — remove one of them, or introduce a narrower interface
  for this module's variant.
```

For a supplied type the hint says to delete this supplier and depend on the
other module, or to supply a type of your own that wraps it.

### One interface bound in two modules

Two modules that cannot see each other each implement the same interface, and both
reach the application — the first compilation that sees them both:

```
Gateway is bound in 2 modules merged into this application:
  1) :stripe (stripe/src/…/StripeGateway.kt:5)
  2) :paypal (paypal/src/…/PayPalGateway.kt:5)
  they cannot see each other, so each claimed com.example.api.Gateway for itself — and one key
  holds one binding, so the merged graph would fail at startup
  hint: keep one implementation per graph; to keep several, put a mark of your own on each
  and on the parameters that choose, or inject Set<Gateway>.
```

### Duplicate binding

Two classes answer the same key in a way no decision can settle — rare, since the
cases above catch the usual causes first:

```
Duplicate binding for com.example.Clock:
  1) SystemClock (…/SystemClock.kt:3)
  2) FakeClock (…/FakeClock.kt:3)
  hint: keep one implementation, or choose with a @Bind rule (GraphRules.kt).
```

### `Set` errors

```
Set<StartupTask> injected by AppInitializer (…/AppInitializer.kt:5) has no implementations.
  hint: implement StartupTask in a project class, or remove the parameter.
```

Decided by the application, the one compilation that sees every module: a set
whose contributors all live in other modules is fine.

```
Conflict for kotlin.collections.Set<StartupTask>: bound directly by TaskBundle (…/TaskBundle.kt:7)
while implementations of StartupTask also aggregate into it.
```

**Fix:** pick one model — a hand-written supplier of the whole set, or the
[plugin pattern](/guide/patterns#the-plugin-pattern-set-i).

## What the graph cannot build

### Missing binding

Something needs a type nothing provides, and it cannot become a graph argument:

```
Missing binding: no provider for com.example.Cache
  injected into: RealUserRepo, constructor param 'cache' (…/RealUserRepo.kt:12)
  hint: implement it in a project class, or let the leaf bubble up to Graph.start.
```

**Fix:** write a project class for it, [supply](/guide/patterns#types-you-don-t-own-supplies-t)
it, or let it become a [graph argument](/guide/inference#graph-arguments). A
parameter with a [default value](/guide/patterns#optional-dependencies-default-values)
is optional and never produces this error.

### A graph argument with two types

```
Graph argument 'timeout' declared with two types:
  Long (…/Http.kt:4)
  Duration (…/Retry.kt:7)
  hint: same name means same argument — rename one parameter.
```

### Constructors

The graph is read from the primary constructor:

```
Cache (…/Cache.kt:3) is in the graph but its primary constructor is private — make it
non-private, or remove the class from the graph.

Cache (…/Cache.kt:3) has 2 constructors and no primary one — the graph is inferred from the
primary constructor.
  hint: declare a primary constructor.
```

### Parameters with no binding identity

A key is one class, so some parameter types cannot be injected:

```
Editor (…/Editor.kt:5): parameter 'cache' has a parameterized type (LruCache<…>) — parameterized
types have no binding identity.
  hint: wrap it in a small class and depend on that.

Editor (…/Editor.kt:5): only () -> T function types can be injected (deferred lookup) — function
types with parameters or suspend functions have no binding identity.
  hint: inject the pieces and build the function where it is used, or declare a small interface.
```

`Set<I>`, `Lazy<T>`, `() -> T` and `Provider<T>` are the generic types Kite
understands. A type that does not resolve at all — a missing import or library —
reports "could not resolve a dependency type (missing from the classpath?)".

### A supplier that cannot supply

[`Supplies<T>`](/guide/patterns#types-you-don-t-own-supplies-t) has rules on both
ends. The supplier must be an ordinary class Kite can construct — not generic, not
private, not a ViewModel, an Android framework class, or a data or value class:

```
HttpVm (…/HttpVm.kt:4) supplies a type but is a ViewModel — androidx owns those instances, so a
ViewModel is an entry point, never a binding.
  hint: move the construction into an ordinary class and supply from there.
```

And what it supplies must be one concrete, non-null class:

```
Http (…/Http.kt:6) supplies OkHttpClient? — a binding is never null.
  hint: supply OkHttpClient, and let the consumer decide what absence means.
```

The other cases — a type parameter, a parameterized type, a type that does not
resolve — say so in the same form.

### Dependency cycle

```
Dependency cycle detected:
  A (…/A.kt:5) → com.example.B (constructor param 'b', …/A.kt:6)
  B (…/B.kt:5) → com.example.A (constructor param 'a', …/B.kt:6)
  hint: break the cycle by injecting Provider<T> or Lazy<T> at one of the sites.
```

**Fix:** make one edge deferred — `Lazy<T>` or `() -> T`. A deferred edge is not a
construction-time edge, so the cycle becomes constructible. See
[patterns](/guide/patterns#defer-or-re-create-lazy-t-and-t). Cross-module cycles
cannot be expressed: Gradle forbids circular project dependencies.

## Lifetimes

### Scope violation

A longer-lived binding depends on a shorter-lived one, which would outlive and
leak it:

```
Scope violation: Singleton AppCache depends on ActivityScoped com.example.SessionState
  Singleton AppCache (…/AppCache.kt:8)
  depends on ActivityScoped SessionState (…/SessionState.kt:4) via constructor param 'session' (…/AppCache.kt:9)
  hint: a longer-lived binding cannot depend on a shorter-lived one — give AppCache an
  equal-or-shorter scope (@Scoped in GraphRules.kt), or mark it @Fresh (a new instance per consumer, no cache).
```

The shorter-lived binding does not have to be a direct parameter. Something without
a scope of its own — a `@Fresh` class, a `Set<I>` — is built in its consumer's
scope, so a singleton that holds one holds whatever *it* holds:

```
Scope violation: Singleton Runner depends on ActivityScoped com.example.ScreenTask through Set<Task>
  …
  Set<Task> has no scope of its own: built in Runner's scope, it keeps ScreenTask for as long
```

This check holds across module boundaries too. See
[lifetimes](/guide/lifetimes#direction-is-enforced).

### Scopes of your own

A [scope of your own](/guide/lifetimes#custom-scopes) opens under the app and closes
on its own schedule, so the build checks it from both sides:

```
Scope violation: Singleton Checkout depends on Session com.example.Cart
  hint: Session instances live only while a Session is open — put Checkout in it too
  (@Scoped(Checkout::class, "Session")), or mark it @Fresh.

Scope violation: Session Cart depends on ActivityScoped com.example.ScreenState
  hint: a scope of your own opens under the app, so Cart cannot hold what an Activity or
  Fragment owns — pass the value in where it is used, or scope Cart to it instead.
```

Two scopes of your own are opened and closed independently, so neither may hold
the other's classes. A scope name has to be letters and digits — `openSession()`
is generated from it.

## Decisions that cannot apply

A rule in `GraphRules.kt` names a real class — the compiler checks that — but it
can still say something the graph cannot do. Each message starts with the rule's
file and line:

| The rule | Why it fails |
|---|---|
| `@Bind(I::class, …)` where nothing in the module implements `I` | if another module provides it, the message names that module: the decision belongs in its rules |
| `@Bind(I::class, to = X::class)` where `X` does not implement `I` | the message lists the implementations there are |
| `@Root(SomeViewModel::class)` | a ViewModel is an entry point already — its adapter is generated |
| `@Scoped` on a ViewModel | the `ViewModelStore` already retains it; a graph scope would double-cache |
| `@Scoped` on a supplied type | a supplied type lives as long as its supplier — put the decision on the supplier |
| `@Scoped` on an interface | a lifetime belongs to the class that is constructed — decide the implementation |
| `@Scoped` on another module's class | decisions live with the module that owns the class |
| `@Scoped` on a class not in the graph | not an implementation, a root, or anyone's dependency |
| `@Fresh` and `@Scoped` for one class, or two `@Scoped` | one class, one lifetime decision |
| `@Fresh` on a local or anonymous class | it has no stable name to be a binding |

## Modules

### Unreachable dependency

Raised only in the application module, about a binding it is merging from another
module:

```
Unreachable dependency: RealMidApi, constructor parameter 'deep'
  provided by: :mid, merged into this application
  its type is not on this module's compile classpath, so the module that
  provides it is missing from the merged graph and resolution would fail at runtime.
  hint: :mid resolves it through an `implementation` dependency, which is not
  transitive — depend on that module here too, or have :mid export it with api(...). Open
  RealMidApi to see the type.
```

`:mid` compiles fine: it can see the module it needs. Your app cannot, because
Gradle's `implementation` is not transitive. The type is not named in the message:
when a type cannot be resolved, KSP has no name to report.

### A module missing from the merge

The same cause, caught where no constructor shows it:

```
Module :data is missing from the merged graph
  :feature resolves keys from it, and :feature is merged into this application
  :data is not on this application's compile classpath, so nothing registers its
  bindings and resolution would fail at runtime
  hint: `implementation` is not transitive — export it with api(project(":data"))
  from :feature, or depend on :data from this module too.

Module :data has a graph this application does not merge
  its classes ship here — it is on the runtime classpath — while its registry is
  not on the compile classpath, so its bindings are registered nowhere
```

**Fix:** depend on that module from the app, or export it with `api(...)` from the
module in between. See [multi-module apps](/guide/multi-module).

## Warnings

### No accessor for a key

Two types whose [generated accessors](/guide/injecting#generated-accessors) would
share a name in one package — a `@Stripe`-marked `Gateway` and a class called
`StripeGateway` with a `@Root`, say:

```
No accessor for com.example.Stripe@com.example.Gateway: com.example.stripeGateway() already looks up com.example.StripeGateway.
Rename one of the types, or look it up with Kite.get (@UncheckedLookup).
```

### Unused binding

```
Unused binding: LegacyTracker (…/LegacyTracker.kt:6) is never injected.
Add @Root(LegacyTracker::class) if it is resolved dynamically, or delete the class.
```

Only *isolated* bindings are flagged — no consumers **and** no dependencies.
Anything with dependencies is usually looked up at runtime through its
[generated accessor](/guide/injecting#generated-accessors), which the processor
cannot see being called, so flagging it would be noise. A binding included by a
`@Root` rule is never flagged, and neither are a library module's implementations:
its consumers are in modules that compilation cannot see.

Suppress it on a class with `@Suppress("kite:unused-binding")`.

### Scoped captures unscoped

```
ActivityScoped SessionPresenter captures unscoped Formatter for its whole lifetime
(…/SessionPresenter.kt:9), while other consumers get fresh instances — if sharing is
intended, remove its @Fresh / "none" decision (singleton is the default).
```

Legal, but usually not what you meant.

### Redundant decisions

`@Scoped(X::class, "singleton")` warns, because singleton is the default and the
line says nothing. So does a `level = …` left on a scope of your own from an earlier
version: the name is the whole declaration now.

## What can still fail at runtime

Three things depend on the order your code runs in, which no build can see:

- a lookup before `Graph.start`;
- a second `Graph.start` with different arguments;
- a lookup into a scope of your own while none is open.

Each throws with the fix in its message, after
[`KiteConfig(onError = …)`](/guide/injecting#when-something-can-still-fail) has
seen it.
