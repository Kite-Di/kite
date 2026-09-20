# Kite

Dependency injection for Android where **the graph is inferred, not declared**.
No annotations, no modules — you write plain classes, and every Dagger-style
guarantee is still checked at compile time.

**[kitedi.com](https://kitedi.com)** — guide, tutorial, every build error explained.

## Get started

**1.** Kite publishes to Maven Central, not the Gradle Plugin Portal:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

**2.** KSP on the build classpath, then one plugin per module with injectable classes:

```kotlin
// build.gradle.kts (root)
plugins { alias(libs.plugins.ksp) apply false }
```

```kotlin
// build.gradle.kts (module)
plugins {
    alias(libs.plugins.android.application)
    id("com.kitedi") version "0.1.0"
}
```

**3.** Write classes — no DI syntax anywhere:

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(api: Api, db: Db) : UserRepo   // that's it. no annotation.
```

**4.** Start the graph:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
```

Build it. Anything nothing provides — an `apiKey`, a timeout — becomes a
parameter of `Graph.start`, and the debug build prints a link to the board.

[Full setup →](https://kitedi.com/guide/getting-started) ·
[Tutorial →](https://kitedi.com/guide/tutorial) ·
[Coming from Hilt →](https://kitedi.com/guide/migration)

> **0.1.0 is early.** The generated surface and the rule annotations may change
> between 0.x releases. Pin an exact version.

## How it works

![How Kite works in your app](docs/architecture.svg)

Implementing an interface *is* the binding. Constructor parameters are the
edges. Everything is a singleton unless you say otherwise.

The few things code cannot say — which of two implementations, a non-default
lifetime, a class resolved only at runtime — go in one committed `GraphRules.kt`
per module, as compile-checked class references:

```kotlin
@Root(FirstPresenter::class)
@Bind(PaymentGateway::class, to = StripeGateway::class)
@Scoped(SessionState::class, "activity")
private object GraphRules
```

## The board

Every debug build opens an infinite canvas of the graph and animates what
changed. Click a node to see where it is provided from and where it is injected.

It never ships: the graph is a build artifact, the board is a host-side process,
and the rule annotations are `SOURCE`-retained and `compileOnly`. No APK gets a
graph, a server, or the `INTERNET` permission — `scripts/check-apk-safety.sh`
asserts it on every CI run.

## Compatibility

KSP is versioned against the exact Kotlin compiler, so Kite tracks specific
versions rather than a range.

| Kite | Kotlin | KSP | AGP | JDK | minSdk |
|---|---|---|---|---|---|
| 0.1.0 | 2.3.21 | 2.3.9 | 9.2.1 | 11+ | 23 |

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md). Questions and bug reports go in
[issues](https://github.com/Kite-Di/kite/issues); the design documents behind
the whole approach are in [`docs/sdd/`](docs/sdd/README.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
