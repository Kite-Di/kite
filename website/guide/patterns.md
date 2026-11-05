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
produce. Any annotation your project declares works, in whichever of its modules;
one from a library or the platform never counts — `@Serializable` or `@Keep` on an
implementation leaves it bound to its interface. A mark nothing provides falls back
to ordinary resolution, so an unrelated annotation on a parameter changes nothing.

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

## Types you don't own — `Supplies<T>` {#types-you-don-t-own-supplies-t}

OkHttp, Retrofit, a `Json` — there is no project class to infer from, so nothing
binds them. Say where one comes from by implementing `Supplies<T>`: implementing an
interface is a binding, and this is that same rule applied to a type you did not
write.

```kotlin
// :core:network — okhttp is a dependency of this module and of nothing else
class Http(
    private val cache: RequestCache,            // project class, resolved
    private val auth: AuthInterceptor,          // project class, resolved
    private val timeoutMillis: Long = 30_000,   // a default is not a graph argument
) : Supplies<OkHttpClient> {
    override val value: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMillis, MILLISECONDS)
        .addInterceptor(auth)
        .cache(cache.asOkHttpCache())
        .build()
}
```

Any module may now take an `OkHttpClient` constructor parameter. `Graph.start` gains
nothing and the application never learns that the module speaks HTTP — which is the
point of an api/impl split in the first place.

A supplier is an ordinary binding in every other respect. Its own constructor is
inferred, its own unprovidable leaves still bubble up, and it is a singleton unless
decided otherwise — so one client with one connection pool needs no annotation. A
`@Scoped` or `@Fresh` decision on the supplier carries to the type it supplies.

Two suppliers of one type is the ambiguity you already know, with the same fix:

```
OkHttpClient is supplied by 2 classes:
  1) Http (core/network/src/Network.kt:12)
  2) CdnHttp (core/network/src/Cdn.kt:8)
  hint: add to GraphRules.kt:  @Bind(OkHttpClient::class, to = Http::class)
```

One class supplies exactly one type — Kotlin forbids implementing a generic
interface twice with different arguments — so a stack of them lives in one file:

```kotlin
class Http(cache: RequestCache) : Supplies<OkHttpClient> { … }
class Parser : Supplies<Json> { … }
class Api(http: OkHttpClient, json: Json) : Supplies<Retrofit> { … }
```

`value` is read once per supplier instance. Under the default lifetime that is once
per process, so `override val value get() = …` does not hand out a new object per
injection — make the *supplier* `@Fresh` for that.

### When it stays a graph argument

A **value** rather than an object — an API key, a timeout, a base URL that differs
per flavour — has nothing to construct. Those still become
[graph arguments](/guide/inference#graph-arguments) and are supplied once at the
`Graph.start` call site, which is the right place for something only the application
knows. A default value covers the case where the module decides it after all.
