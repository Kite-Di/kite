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

::: warning One limit
`Set<I>` collects implementations **per module**. A set injected in `:app` does
not currently pick up implementations declared in `:feature:orders:impl`. See
[limitations](/guide/limitations).
:::

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
