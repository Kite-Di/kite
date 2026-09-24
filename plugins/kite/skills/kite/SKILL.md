---
name: kite
description: Set up Kite DI in an Android/Kotlin project, migrate a project off Hilt or Dagger onto Kite, wire a class/ViewModel/Composable into the graph, or fix a failing Kite build. Use when the user says "install Kite", "add Kite DI", "set up dependency injection with Kite", "migrate from Hilt/Dagger", "kite doesn't compile", or hits a Kite build message (duplicate binding, missing binding, dependency cycle, scope violation, unreachable dependency, scope level collision).
---

# Kite

Kite is dependency injection for Android in which the graph is **inferred from
constructors** rather than declared. A class someone takes as a constructor
parameter is a binding; the sole implementation of a project interface is bound
to it; anything nothing provides becomes an argument of the generated
`Graph.start`. There is no `@Inject`, no `@Module`, no `@Provides`, no component.
Only genuine decisions are written down, in one `GraphRules.kt` per module.

Reference docs live at <https://kitedi.com> — that site is the source of truth;
the files under `references/` are the working subset for this skill.

## Route before touching anything

Read the project first. These four facts pick the work:

```bash
grep -rn 'com\.kitedi' --include=*.gradle.kts --include=*.gradle --include=*.toml .
grep -rln 'dagger\.hilt\|@HiltAndroidApp\|@AndroidEntryPoint\|@HiltViewModel\|dagger\.Module' --include=*.kt --include=*.java --include=*.gradle.kts .
grep -rn 'kotlin\s*=\|ksp\s*=\|agp\s*=\|androidGradlePlugin' gradle/libs.versions.toml 2>/dev/null
grep -rn 'compileSdk\|minSdk' --include=*.gradle.kts . | head
```

| State | Run phases |
|---|---|
| No `com.kitedi` anywhere, no Hilt/Dagger | 1 → 2 → 4 → 5 → 6 |
| No `com.kitedi`, Hilt or Dagger present | 1 → 2 → 3 → 4 → 5 → 6 |
| `com.kitedi` already applied | 5 → 6 (or just 6 if the ask is a build error) |

Phases are cumulative, not alternatives. A migration is a fresh install plus a
deletion pass. Never ask the user which mode to run — the greps decide.

Announce the route in one line before starting, then work through it. Do not stop
between phases for approval; stop only for the two blocking cases named in
phase 1 and for an ambiguity phase 6 genuinely cannot resolve.

## 1. Preflight

Read `references/gradle.md` → *Requirements*. Kite tracks exact Kotlin/KSP pairs
because KSP is versioned against the compiler, so this is a hard gate, not advice.

- Kotlin or KSP below the supported pair, or AGP below 9 → propose the exact
  version bumps as an edit to `gradle/libs.versions.toml` and apply them.
- `compileSdk` below 34, or `minSdk` below 23 → **stop and ask**. Raising either
  is a product decision with release consequences, not a wiring detail.
- No Gradle project, or not Kotlin → **stop**. Kite is KSP-based; there is
  nothing to install.

## 2. Gradle wiring

Follow `references/gradle.md`. Three edits, then a build that must pass before
any Kotlin is touched:

1. `settings.gradle.kts` — `mavenCentral()` in **both** `pluginManagement` and
   `dependencyResolutionManagement`. Kite publishes to Maven Central, not the
   Gradle Plugin Portal.
2. root `build.gradle.kts` + `gradle/libs.versions.toml` — KSP on the build
   classpath with `apply false`, and the `kite` plugin alias.
3. every module that contains injectable classes — and only those — gets
   `alias(libs.plugins.kite)`.

Deciding *which* modules is the one judgement call here: a module with no class
anyone constructs (a design system, a pure-contract `:api` module) must not
apply the plugin. The rule and the check are in `references/gradle.md`.

Then `./gradlew :app:assembleDebug`. A failure at this point is a toolchain
problem, not a graph problem — fix it here rather than carrying it into phase 6.

## 3. Strip Hilt / Dagger (migration only)

Follow `references/hilt.md`. Most of a migration is deletion, and it is safe to
be aggressive: everything you delete that mattered comes back as a build error in
phase 6 with the exact line to write.

Delete the annotations, delete the `@Binds` modules, delete Hilt's plugin and
kapt/KSP entries. Turn each `@Provides` for a library type into a graph argument
at the `Graph.start` call site. Do not leave Hilt and Kite applied to the same
module — two processors generating entry points for one Activity is a confusing
failure.

## 4. Start the graph

`Graph.start(this)` in `Application.onCreate`, after `super.onCreate()`. The
façade is generated for the application module; its parameters appear as the
build discovers leaves nothing provides, so expect this call to grow in phase 6.

## 5. Call sites

Follow `references/call-sites.md`. Everywhere Android constructs the object
instead of Kite — Activity, Fragment, View, ViewModel, `@Composable`, Service —
there is one specific form, and the table gives it.

Two judgements recur:

- **Prefer a constructor parameter.** `by injected()` is for classes Android
  constructs. In a class Kite creates, a field lookup hides the edge from the
  graph and from the board — use a parameter.
- **A plain class resolved only at runtime needs `@Root`.** `by injected()` and
  `Kite.get<T>()` are invisible to a processor reading constructors. A class
  *behind an interface* is already in the graph and needs nothing — check which
  case you have before adding the rule.

## 6. Build until the graph is correct

Loop: `./gradlew :app:assembleDebug` → read the message → apply the fix →
repeat. Every message names the file and line of **both** sides and ends in a
`hint:` line; `references/errors.md` maps each one to its fix and to the exact
`GraphRules.kt` syntax.

This phase is what makes the earlier ones safe to get wrong. The validator is
total: ambiguity, missing bindings, cycles and scope violations are all compile
errors, so a graph that builds is a graph that resolves.

Ambiguity is the one place to involve the user. `Duplicate binding` means two
implementations and only they know which the app wants — if the choice is not
obvious from the code (a `Real*` vs a `Fake*`, a `Caching*` decorator), ask
rather than guess, then write the `@Bind` into the **owning** module's rules
file.

Read `build/kite/graph.json` (written on every debug build, per module) when a
message needs context — what else binds a key, what a module exports. It is the
same data the board renders and it is on disk, so there is nothing to start.

## 7. Report

State what changed and what the user now owns:

- modules that got the plugin, and any deliberately left without it;
- every `GraphRules.kt` written, with the decision each line encodes;
- the final `Graph.start` signature — each argument is a value they supply;
- anything Hilt did that has no Kite equivalent and was deleted rather than
  translated.

Then the board: a debug build prints `Dependency board: http://localhost:8394`
from `:app:kiteBoard`. Point at it — it is how they see the graph they did not
write.

## Things to get right

- **Do not invent registration.** No module, no factory, no provider function. If
  a type has no project class, it is a graph argument, built with ordinary code
  at the `Graph.start` call site.
- **Decisions live with the owner.** `@Bind`/`@Scoped` for a `:core:network`
  class go in `:core:network/…/GraphRules.kt`, even when the error surfaced in
  `:app`.
- **Name graph arguments globally.** The parameter *name* is the identity, so two
  unrelated `String` leaves called `key` silently unify. Write `profileApiKey`.
- **Singleton is the default.** Never write `@Scoped(X::class, "singleton")` — it
  warns, and it says nothing.
- **Never add the runtime dependency by hand.** `id("com.kitedi")` applies KSP
  and wires `runtime`, `processor` and the `compileOnly` rules artifact. A
  hand-written `implementation("com.kitedi:…")` line means something went wrong.
