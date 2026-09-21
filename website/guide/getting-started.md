# Getting started

Five steps from an empty Android project to a working graph. If you would rather
be walked through a small app end to end, take the [tutorial](/guide/tutorial)
instead — it covers the same ground more slowly.

## Requirements

| | |
|---|---|
| Kotlin | 2.3.x |
| KSP | matched to your Kotlin version (`2.3.21` → `2.3.9`) |
| Android Gradle Plugin | 9.x |
| JDK | 11 or newer |
| `compileSdk` | 36.1 — a hard requirement, the AAR metadata rejects lower |
| `minSdk` | 23 |

```kotlin
android {
    compileSdk { version = release(36) { minorApiLevel = 1 } }
}
```

KSP is versioned against the exact Kotlin compiler, so the two always move
together. Kite compiles against KSP's API, which is why it tracks specific
versions rather than a range — the table above is the combination 0.1.0 was built
and tested against.

## 1. Let Gradle find the plugin

Kite publishes to Maven Central, not the Gradle Plugin Portal, so the plugin
repositories in `settings.gradle.kts` need to include it:

```kotlin
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

## 2. Put KSP on the build classpath

Kite's plugin applies KSP for you, but KSP has to be resolvable from the root
build file — the same prerequisite Hilt's plugin has:

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

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

## 3. Apply the plugin

In every module that contains injectable classes — and only those:

```kotlin
plugins {
    alias(libs.plugins.android.application)   // or android-library
    id("com.kitedi") version "0.1.0"
}
```

That single id does what used to be a KSP block plus three dependencies: it
applies KSP, adds `runtime`, `processor` and the `compileOnly` rules artifact, and
sets every processor option (module path, aggregation on the app module, variant
handling, graph output paths).

Modules with nothing injectable — a design system, a pure-contract `:api` module —
should not apply it. See [multi-module apps](/guide/multi-module).

::: tip Version in one place
Prefer the version catalog over repeating `version "0.1.0"`:
`kite = { id = "com.kitedi", version.ref = "kite" }`, then `alias(libs.plugins.kite)`.
:::

## 4. Write a class

No annotation, no module, no registration:

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(private val api: Api) : UserRepo {
    override fun userName() = api.fetchName()
}

class Api(private val http: HttpClient)
```

Three bindings now exist. `RealUserRepo` is the sole implementation of a project
interface, so it is bound to `UserRepo`. `Api` is a class someone takes as a
constructor parameter, so it is in the graph. Each is a shared instance app-wide.

## 5. Start the graph

The processor generates a startup façade for your app module. Call it from
`Application.onCreate`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
```

Build. If something in your graph needs a value nothing provides — a timeout, an
API key — the build tells you, and `Graph.start` grows a parameter for it:

```kotlin
class HttpClient(cache: RequestCache, timeoutMillis: Long)   // nothing provides Long…

// …so the generated façade becomes:
Graph.start(this, timeoutMillis = 5_000)
```

These are [graph arguments](/guide/inference#graph-arguments): the parameter name
is the identity, which is what replaces `@Named` and construction modules.

## What you get

Your debug build now prints a link:

```
> Task :app:kiteBoard

  Dependency board: http://localhost:8394  (starting)
```

Open it to see the graph you did not write. [The board →](/guide/board)

## Where to next

- [How the graph is inferred](/guide/inference) — the rules the processor applies
- [Decisions — GraphRules.kt](/guide/rules) — the few things you do write down
- [Injecting](/guide/injecting) — Activities, Fragments, ViewModels, Compose
- [Build errors](/guide/errors) — every message the validator can produce
