<p align="center">
  <img src="/docs/hero_logo.png" alt="Kite — Dependency Injection for Android" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Kite-Di/kite/releases">
    <img src="https://img.shields.io/badge/Kite-0.1.1-446eaa" alt="Kite 0.1.1">
  </a>
  <img src="https://img.shields.io/badge/platform-Android-6555aa" alt="Android">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-Apache%202.0-526777" alt="Apache 2.0 license">
  </a>
</p>

<p align="center">
  Dependency injection for Android where <strong>the graph is inferred, not declared.</strong>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="https://kitedi.com">Documentation</a> ·
  <a href="https://kitedi.com/guide/tutorial">Tutorial</a> ·
  <a href="#the-board">The board</a> ·
  <a href="#compatibility">Compatibility</a>
</p>

Write plain classes. Kite infers their relationships and checks Dagger-style
guarantees at compile time. Your injectable classes need no annotations or DI modules;
exceptions live in a small, compile-checked `GraphRules.kt` file.

> **0.1.1 is early.** The generated surface and the rule annotations may change
> between 0.x releases. Pin an exact version.

## Kite at a glance

Implementing an interface *is* the binding. Constructor parameters are the edges.

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(api: Api, db: Db) : UserRepo   // that's it. no annotation.
```

- **An inferred graph.** Plain classes describe their own dependencies.
- **Compile-time checks.** Graph guarantees are checked during the build.
- **A visual debug board.** Explore dependencies and see what changed.

Everything is a singleton unless you say otherwise.

## Quick start

### 1. Configure plugin repositories

Kite publishes to Maven Central, not the Gradle Plugin Portal.
Add Maven Central to plugin resolution in `settings.gradle.kts`:

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

### 2. Apply the plugin

Put KSP on the build classpath, then apply Kite to each module with injectable classes.

```kotlin
// build.gradle.kts (root)
plugins { alias(libs.plugins.ksp) apply false }
```

```kotlin
// build.gradle.kts (module)
plugins {
    alias(libs.plugins.android.application)
    id("com.kitedi") version "0.1.1"
}
```

Use the plain classes shown above to describe your dependencies.
For the full setup, including the surrounding project configuration, see the
[getting started guide](https://kitedi.com/guide/getting-started).

### 3. Start the graph

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

## How it works

![How Kite works in your app](docs/architecture.svg)

| Part of your code | What it tells Kite |
| --- | --- |
| An interface implementation | Which class provides that interface. |
| Constructor parameters | Which dependencies a class needs. |
| `GraphRules.kt` | Explicit choices the code cannot express on its own. |

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

The board never ships in your app:

- The graph is a build artifact.
- The board is a host-side process.
- Rule annotations are `SOURCE`-retained and `compileOnly`.

No APK gets a graph, a server, or the `INTERNET` permission.
[`scripts/check-apk-safety.sh`](scripts/check-apk-safety.sh) asserts this on every CI run.

## Compatibility

KSP is versioned against the exact Kotlin compiler, so Kite tracks specific
versions rather than a range.

| Kite | Kotlin | KSP | AGP | Gradle | JDK | compileSdk | minSdk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0.1.1 | 2.2.21–2.3.21 | matched to Kotlin | 8.13.2–9.4.1 | 9.4.1+ | 11+ | 34 | 23 |

Kite pins none of these. The plugin takes KSP as `compileOnly` and declares no
AGP dependency at all, so the versions are yours; the table is the range CI
builds a real consumer project against
([`demo/consumer_app`](demo/consumer_app)), with the configuration cache on.
Kotlin and KSP move together — that is KSP's constraint, not Kite's.

`compileSdk` is a floor, not a recommendation — the AAR metadata rejects
anything lower. Higher is fine, and normal: it may exceed your `targetSdk`.

## Go further

| Resource | What you'll find |
| --- | --- |
| [Getting started](https://kitedi.com/guide/getting-started) | Full project setup. |
| [Tutorial](https://kitedi.com/guide/tutorial) | A walkthrough of using Kite. |
| [Coming from Hilt](https://kitedi.com/guide/migration) | A guide to migrating from Hilt. |
| [Documentation](https://kitedi.com) | Guides, tutorials, and build errors explained. |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
Questions and bug reports go in [issues](https://github.com/Kite-Di/kite/issues).

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

<p align="center">
  Kite · Dependency Injection for Android<br>
  <a href="#kite-at-a-glance">Back to top ↑</a>
</p>
