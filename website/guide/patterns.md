# Patterns

The things other frameworks spell with an annotation, spelled with ordinary
Kotlin instead.

## Defer or re-create — `Lazy<T>` and `() -> T` {#defer-or-re-create-lazy-t-and-t}

Standard-library types, so the injection site imports nothing from the framework:

```kotlin
class Editor(
    private val parser: Lazy<HeavyParser>,   // kotlin.Lazy: created on first .value, then cached
    private val cursors: () -> Cursor,       // a fresh Cursor per call (Cursor is @Fresh)
)
```

The framework's own `Provider<T>` and `Lazy<T>` work identically if you prefer the
explicit types — `Provider<T>` *is* a `() -> T`.

Either form also breaks a constructor cycle: `A → B → () -> A` is legal, because
the deferred edge is not a construction-time edge. That is the hint the
[cycle error](/guide/errors#dependency-cycle) gives you.

## Optional dependencies — default values {#optional-dependencies-default-values}

A parameter with a default value is optional. If the type has a binding it is
injected; if not, the default is used — no build error, and it never becomes a
[graph argument](/guide/inference#graph-arguments):

```kotlin
class Uploader(
    private val http: HttpClient,
    private val retries: Int = 3,     // no Int binding → 3 is used
)
```

## The plugin pattern — `Set<I>` {#the-plugin-pattern-set-i}

Implement the interface anywhere; inject the whole collection. Adding an
implementation touches no other file — it joins the set, and the board,
automatically:

```kotlin
interface StartupTask { val name: String; fun run() }

class WarmUpCacheTask(cache: RequestCache) : StartupTask { … }
class TrackLaunchTask(analytics: Analytics) : StartupTask { … }

class AppInitializer(private val tasks: Set<StartupTask>) {
    fun runAll() = tasks.forEach { it.run() }
}
```

This is `@IntoSet`/multibindings without the vocabulary.

**It crosses module boundaries.** A feature module that implements the interface
joins the set by existing on the build path — it registers nothing, and no module
lists the others. The application composes the set, because it is the one
compilation that sees every contributor; the aggregator itself may live wherever
you keep the contract, including a module the features depend on.

See [multi-module apps](/guide/multi-module#a-plugin-architecture) for the shape
that falls out of this.

## Choosing between implementations — marks {#choosing-between-implementations-marks}

Two implementations of one interface, and the consumer knows which one it wants:
put your own annotation on the implementation and the same one on the parameter.

```kotlin
// wherever the contract lives
@Retention(AnnotationRetention.SOURCE) annotation class Stripe
@Retention(AnnotationRetention.SOURCE) annotation class PayPal

@Stripe class StripeGateway : Gateway     // one module
@PayPal class PayPalGateway : Gateway     // another

class Checkout(
    @Stripe private val primary: Gateway,
    @PayPal private val fallback: Gateway,
)
```

Kite defines no annotation for this and there is no meta-annotation to apply:
each side is read in its own module, and what ties them together is the key they
produce. Any annotation you own works; platform annotations never count, and a
mark nothing provides falls back to ordinary resolution, so an unrelated
annotation on a parameter changes nothing.

A marked implementation keeps the key of its own class, so importing the module
and depending on the class directly works as it always did. Two implementations
carrying the *same* mark is a build error — a mark selects one.

Use [`@Bind`](/guide/rules#bind) instead when the choice is the application's and
the consumer should stay unaware of it.

## The strategy pattern — a key on the interface

Need to pick one implementation by name? Put the key on the interface. There is
no `@IntoMap`, no `@MapKey`, and nothing framework-shaped to learn:

```kotlin
interface PayloadParser { val format: String; fun parse(raw: String): String }

class Decoder(private val parsers: Set<PayloadParser>) {
    fun decode(format: String, raw: String) =
        parsers.first { it.format == format }.parse(raw)
}
```

## Types you don't own

OkHttp, Room, a `String` from `BuildConfig` — there is no project class to infer
from, so they become [graph arguments](/guide/inference#graph-arguments) and get
built with ordinary code at the `Graph.start` call site. That is the replacement
for a module full of `@Provides` functions.
