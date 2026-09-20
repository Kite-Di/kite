# Decisions — GraphRules.kt

Inference covers what the code says. Some things the code genuinely cannot say:
which of two implementations you meant, that a class should live only as long as
an Activity, that a class nobody constructs is still needed. Those are
**decisions**, and they are the only thing you write down.

One optional file per module — any name; `GraphRules.kt` by convention. Committed,
never shipped.

```kotlin
import com.kite.di.rules.Bind
import com.kite.di.rules.Root
import com.kite.di.rules.Scoped

// a *plain* class resolved only at runtime (`by injected()` / Kite.get) — inference
// can't see call sites (a class behind an interface needs no root: it is already included)
@Root(LegacyTracker::class)
// choose among multiple implementations
@Bind(PaymentGateway::class, to = StripeGateway::class)
// lifetimes beyond the default singleton
@Scoped(SessionState::class, "activity")
@Scoped(SessionCart::class, "Session", level = 10)
private object GraphRules
```

## Why a file and not annotations on the classes

Because a decision is not a property of the class. `StripeGateway` does not know
it won a vote against `PayPalGateway`; the *app* decided that. Putting the
decision on the class spreads the app's configuration across files that have no
business holding it — and makes it invisible. In one file per module you can read
every choice the module makes in a few seconds.

## Why class references and not strings

`@Bind(PaymentGateway::class, to = StripeGateway::class)` is compile-checked. A
typo is an ordinary unresolved reference — the IDE marks it red before the build
runs, "find usages" finds it, and a rename refactoring updates it. The file
cannot rot.

The annotations are `SOURCE`-retention and pulled in `compileOnly`, so nothing
here reaches the APK by construction, not by convention.

## The three annotations

### `@Root`

Declares a consumer the compiler cannot see. Needed for a plain class resolved
only at runtime, or exported to another module. See
[what inference cannot see](/guide/inference#what-inference-cannot-see).

### `@Bind`

Picks the implementation when a project interface has more than one. With a single
implementation it is unnecessary — inference already binds it.

### `@Scoped`

Gives a class a lifetime other than the default singleton. Built-in names are
`"activity"`, `"fragment"` and `"none"`; a custom name needs its own `level`.
See [lifetimes](/guide/lifetimes).

`@Scoped(X::class, "singleton")` earns a warning: singleton is the default, so
the line says nothing.

## The one decision that lives on the class

`@Fresh` — a new instance per injection, never shared:

```kotlin
import com.kite.di.rules.Fresh

@Fresh
class DefaultGreetingUseCase(private val repository: UserRepository) : GreetingUseCase
```

"Never shared" is part of the class's own contract — a caller reading the class
needs to know it, so it belongs where the class is read. Everything else is the
app's choice and belongs in the rules file.

## Decisions live with the owner

`@Scoped`/`@Bind` for a `:core:network` class belong in `:core:network`'s rules
file. The build says so if you put them elsewhere, and an ambiguity you hit from
`:app` points you at the owning module's file. See
[multi-module apps](/guide/multi-module).
