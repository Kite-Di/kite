# DependencyInjector

A dependency injection framework for Android where **the graph is inferred, not
declared**: no annotations, no modules — you write plain classes, the KSP
processor reads what the code already says, and every Dagger-style guarantee is
checked at compile time. Plus a live **web board**: an infinite-canvas
(Excalidraw-style) view of your dependency graph that updates as you change code
and shows, for any dependency, exactly **where it is provided from** and **where
it is injected**.

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(api: Api, db: Db) : UserRepo   // that's it. no annotation.
```

**The code is the module; you only write down decisions.** Implementing the
interface *is* the binding: compile-time-validated, a shared singleton like
everything in the graph, a node on the board. Decisions the code can't express
(which of two implementations, a non-default lifetime) live in one committed
file per module — `GraphRules.kt`, as `@Root`/`@Bind`/`@Scoped` annotations with
compile-checked class references — except the one decision that belongs on the
class itself: `@Fresh`, a new instance per injection instead of the default
singleton.

![How Kite works in your app](docs/architecture.svg)

New here? Start at **[kitedi.com](https://kitedi.com)** — getting started, a
step-by-step tutorial, and a reference for every build error. The
[10-minute guide](docs/GUIDE.md) in this repository covers the same ground on one
page.

> **0.1.0 — early.** The generated surface, the rule annotations and the board
> protocol may change between 0.x releases. Pin an exact version.

## Quickstart (5 steps)

1. **One plugin** (see `app/build.gradle.kts` for the working example):

```kotlin
// settings.gradle.kts — Kite publishes to Maven Central, not the Gradle Plugin Portal
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts of every module with injectable classes
plugins {
    alias(libs.plugins.android.application)
    id("com.kitedi") version "0.1.0"
}
```

That's the entire setup: the plugin applies KSP, adds the runtime + processor
dependencies, and configures the processor (graph export into the build dir,
provenance stripping for release, the `:kite:rules` decisions vocabulary as
`compileOnly`). It only needs the KSP plugin on the build classpath — root
`build.gradle.kts`: `alias(libs.plugins.ksp) apply false`.

2. **Write classes.** No DI syntax anywhere:

```kotlin
class Analytics { fun track(event: String) { … } }

class NetworkUserRepository(api: ApiClient, analytics: Lazy<Analytics>) : UserRepository

class CounterViewModel(analytics: Analytics) : ViewModel()
```

The processor infers the graph from declarations: implementing a project
interface binds you to it; constructor parameters are the edges; every binding
is one shared singleton unless decided otherwise (`@Fresh` on a class = a new
instance per injection); a `Set<I>` parameter collects every implementation of
`I` (multibindings without `@IntoSet`); a leaf parameter nothing provides (an
`apiKey: String`, a timeout, a library type) **bubbles up** to become a
parameter of the generated `Graph.start(…)` — configuration without modules.
`Lazy<T>` / `() -> T` defer, default parameter values make a dependency optional.

3. **Start** once — with the generated façade, whose signature *is* your
   configuration surface:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, apiKey = BuildConfig.API_KEY, timeoutMillis = 5_000)
    }
}
```

Use it: `by injected()` in Activities/Fragments, generated `by counterViewModel()`
adapters for ViewModels (rotation-retained, runtime arguments become plain
function parameters — no assisted injection concept needed). Compose is
first-class: apply the Compose plugin and every ViewModel also gets a generated
`rememberCounterViewModel()` composable, plus `injected<T>()` for graph values.

4. **Decide** only when the code can't. Two implementations of one interface, a
   custom lifetime, a class resolved only at runtime — one annotation each on the
   `GraphRules.kt` holder, and the build error (or a board decision card) gives you
   the exact line to paste:

```kotlin
@Root(FirstPresenter::class)                              // resolved via `by injected()`
@Bind(PaymentGateway::class, to = StripeGateway::class)
@Scoped(SessionState::class, "activity")
@Scoped(SessionCart::class, "Session", level = 10)        // custom scope, opened/closed manually
private object GraphRules
```

Missing bindings, duplicates, cycles, and scope violations are still build errors
with file:line provenance of both the provider and the injection site.

5. **See the graph — on your development machine only.** Every debug build prints
   the link and starts the board for you:

```
> Task :app:kiteBoard
  Dependency board: http://localhost:8394  (starting server…)
```

The board server watches `app/build/kite/graph.json`. Every rebuild in
Android Studio rewrites it, and the open board animates what changed — which
dependency appeared, where it is provided from, where it is injected. (Auto-start
is skipped for `test` and under CI; run it yourself with `./gradlew kiteBoard`, or
disable with `-Pkite.startBoard=false`.)

**The graph never leaves your machine.** No APK — debug or release — contains
the graph, a server, the board, or even the INTERNET permission
(`scripts/check-apk-safety.sh` enforces this). `GraphRules.kt` (`SOURCE`-retained,
`compileOnly`) and `graph.json` are dev-side only; generated code keys bindings by
`Class` references, so R8 renaming is safe and no source-name strings ship. A
debug-only on-device inspector (`:kite:inspector`) can report the live runtime to
the board over `adb`; it binds to `127.0.0.1` only, and `:kite:inspector-noop` is
its empty stand-in for release builds.

## Status

Implemented: inference scanner (rules R1–R5) + `GraphRules.kt`/`@Fresh` decisions,
singleton-by-default lifetimes, KSP validation
V1–V5 + codegen (factories, registries, ViewModel adapters, `Graph` façade) +
graph + decisions export, Android runtime (scope tree, lifecycle integration),
opt-in inspector server, web board with decision cards. The full Software Design
Documents live in [`docs/sdd/`](docs/sdd/README.md) — docs 01–07 describe the
system as it is; docs 09–11 are the decision records behind the inferred paradigm.

## Repository layout

```
kite/graph-core       graph model + JSON + diff (shared compile-time/runtime/board)
kite/processor        KSP: inference + validation + codegen + graph.json export
kite/runtime          Android runtime container + scope tree
kite/compose          injected<T>() composable + injectedViewModel plumbing
kite/gradle-plugin    id("com.kitedi") — one-plugin setup (included build)
kite/inspector        debug-only on-device server reporting the live runtime
kite/inspector-noop   release stand-in (empty API)
kite/board            host-side board server; carries the built web UI in its jar
webboard/                 TypeScript + Vite infinite-canvas frontend
website/                  VitePress sources for kitedi.com
app/                      demo app shell — navigation + startup tasks, zero DI annotations
                          (its only decisions: four @Scoped lifetimes in .../di/GraphRules.kt)
core/network              demo http stack (ApiClient, parsers) — interface + implementation
                          throughout, so R1 exports it without a single decision; its leaf
                          params (apiKey, timeoutMillis) bubble into the app's Graph.start
core/analytics            demo analytics + crash reporting — two Analytics implementations,
                          so its GraphRules.kt holds the one @Bind that picks between them
core/designsystem         Compose theme + components — no Kite plugin: nothing injectable
feature/profile/api|impl  vertical feature, clean-architecture cut: interface in :api, R1
feature/orders/api|impl   binds the :impl cross-module; Compose screens live in the impls
scripts/                  check-apk-safety.sh — asserts APKs contain no graph/server/board
```

## Verifying APKs stay clean

```
./gradlew :app:assembleDebug :app:assembleRelease && ./scripts/check-apk-safety.sh
```

asserts that neither APK contains Ktor, inspector classes, web board assets, a
`graph.json`, or the INTERNET permission. Release builds additionally strip the
file/line provenance strings from generated registries.

## Compatibility

KSP is versioned against the exact Kotlin compiler, so Kite tracks specific
versions rather than a range. Each release is built and tested against:

| Kite | Kotlin | KSP | AGP | JDK | minSdk |
|---|---|---|---|---|---|
| 0.1.0 | 2.3.21 | 2.3.9 | 9.2.1 | 11+ | 23 |

## Artifacts

All published under `com.kitedi` on Maven Central. The Gradle plugin pulls in
what it needs — you normally declare none of these by hand.

| Artifact | What it is |
|---|---|
| `com.kitedi` (plugin) | the `id("com.kitedi")` plugin |
| `runtime` | Android runtime: keys, registries, scopes |
| `processor` | the KSP processor |
| `rules` | `@Root`/`@Bind`/`@Scoped`/`@Fresh` — `compileOnly`, never in an APK |
| `compose` | Compose helpers and adapters |
| `graph-core` | the shared graph model |
| `board` | host-side board server |
| `inspector` / `inspector-noop` | debug-only on-device reporting, and its release stand-in |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports and questions are welcome in
[issues](https://github.com/Kite-Di/kite/issues).

## License

```
Copyright 2026 Kite contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
