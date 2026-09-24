# Gradle wiring

<https://kitedi.com/guide/getting-started>

## Requirements

| | |
|---|---|
| Kotlin | 2.3.x |
| KSP | matched to the exact Kotlin version (`2.3.21` → `2.3.9`) |
| Android Gradle Plugin | 9.x |
| JDK | 11 or newer |
| `compileSdk` | 34 or higher — a floor the AAR metadata enforces |
| `minSdk` | 23 |

`2.3.21` / `2.3.9` is the pair 0.1.1 was built and tested against. KSP is
versioned against the compiler, so the two always move together — for a different
Kotlin version take the matching KSP release rather than guessing one.

`compileSdk` may exceed `targetSdk`, so a Wear OS or TV app still targeting 34–35
compiles fine.

## 1. Repositories

Kite publishes to Maven Central, not the Gradle Plugin Portal.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()        // ← Kite's plugin lives here
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

A project using `RepositoriesMode.FAIL_ON_PROJECT_REPOS` needs nothing extra —
that mode is already compatible.

## 2. KSP on the build classpath

Kite's plugin applies KSP per module, but KSP must be resolvable from the root
build file — the same prerequisite Hilt's plugin has.

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false          // ← add this
}
```

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.3.21"
ksp = "2.3.9"
kite = "0.1.1"

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
kite = { id = "com.kitedi", version.ref = "kite" }
```

A project with no version catalog can write `id("com.kitedi") version "0.1.1"`
in each module, but prefer adding the catalog entry — the version then lives in
one place.

## 3. Apply per module

```kotlin
plugins {
    alias(libs.plugins.android.application)   // or android-library
    alias(libs.plugins.kite)
}
```

That one id replaces what used to be a KSP block plus three dependencies: it
applies KSP, adds `runtime`, `processor` and the `compileOnly` rules artifact,
and sets every processor option — module path, aggregation on the app module,
variant handling, graph output paths. **Never add those dependencies by hand.**

If the Compose compiler plugin is applied to the module, the Kite plugin notices
and adds the Compose helpers and a `@Composable remember…` form for every
ViewModel adapter. Nothing to configure.

## Which modules get it

> Every module that contains injectable classes — and only those.

Injectable means: a class another class takes as a constructor parameter, an
implementation of a project interface, a `ViewModel`, or a class resolved at
runtime through `by injected()` / `Kite.get`.

Modules that must **not** apply it:

- a pure-contract module — interfaces and data classes only, the `:api` half of
  an api/impl split;
- a design system / resources module with no injectable class;
- a module of pure functions or extensions.

Applying it to such a module costs a KSP round that produces an empty registry.
Leaving it off a module that does have injectables is the real error, and it
surfaces as a missing binding in the app.

Check each candidate module with:

```bash
grep -rln 'class .*(.*:.*)\|: ViewModel()\|by injected()\|Kite\.get' --include=*.kt <module>/src/main
```

Cross-module notes:

- Interface implementations travel across module boundaries for free, including
  across an api/impl split — the implementation binds to the interface declared
  elsewhere and is injectable from the app with no ceremony.
- A **plain** class (not behind an interface) consumed only by *other* modules is
  not in its own module's inferred graph. It needs `@Root(Foo::class)` in the
  owning module's `GraphRules.kt`. Putting it behind an interface removes the
  question entirely, and is usually the better fix.
- `implementation` is not transitive: if `:app` merges a graph fragment whose
  types it cannot see, the build stops with `Unreachable dependency`. Fix by
  depending on that module from `:app` too, or by exporting it with `api(...)`.

## 4. Start the graph

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
```

The façade is generated for the application module. Every leaf the graph cannot
provide becomes a named parameter on it, so the call grows as the build discovers
them:

```kotlin
Graph.start(this, apiKey = BuildConfig.API_KEY, timeoutMillis = 5_000)
```

Register the `Application` subclass in `AndroidManifest.xml` if it is not
already (`android:name=".App"`).

## Verify

```bash
./gradlew :app:assembleDebug
```

A debug build also prints the board and writes the graph to disk:

```
> Task :app:kiteBoard
  Dependency board: http://localhost:8394  (starting)
```

- `build/kite/graph.json` — the graph fragment, per module, rewritten every debug
  build. Dev-only, never packaged.
- `build/kite/decisions.json` — pending decisions the board renders as cards.
