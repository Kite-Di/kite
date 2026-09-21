# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
semantic versioning — with the caveat that 0.x makes no stability promise.

## [Unreleased]

## [0.1.1] — 2026-09-21

### Fixed

- **The configuration cache works.** `kiteBoard` held a detached `Configuration`,
  which Gradle cannot serialize, so every consumer's debug build fell back to no
  configuration cache — on by default in new Gradle 9 projects.
- **The board jar is no longer downloaded when the board will not start.** The
  task depended on the resolved classpath unconditionally, so even
  `-Pkite.startBoard=false` and CI builds fetched it.
- The plugin reads its Gradle properties through `providers.gradleProperty`
  rather than `findProperty`, which searched parent projects and is forbidden
  under isolated projects.

### Changed

- The Android artifacts now require `compileSdk 34` instead of `36.1`. The old
  floor came from a project template, not from any API the library uses, and it
  shut out apps that must target 35 (Wear OS, Automotive) or 34 (Android TV,
  XR) and keep `compileSdk` in step with `targetSdk`. 34 is as low as the
  Compose dependencies allow.
- The javadoc jars of `rules`, `graph-core`, `processor`, `board` and
  `gradle-plugin` contain the API documentation. In 0.1.0 they were empty: the
  stock javadoc tool reads no Kotlin, and Dokka was not applied.

## [0.1.0] — 2026-09-21

First public release.

### Added

- **Inferred graph.** A KSP processor reads the graph out of ordinary Kotlin:
  implementing a project interface is the binding, constructor parameters are the
  edges. No annotations on your classes.
- **Decisions as code.** `@Root`/`@Bind`/`@Scoped` on a per-module `GraphRules.kt`
  holder, written as compile-checked class references. `SOURCE` retention and
  `compileOnly`, so none of it can reach an APK. `@Fresh` lives on the class.
- **Singleton by default.** Everything in the graph is one shared instance;
  `"activity"` and `"fragment"` scopes follow the Android lifecycle, and custom
  scopes are opened and closed by hand.
- **Compile-time validation.** Missing bindings, ambiguity, duplicates, cycles and
  scope violations are build errors carrying the file and line of both sides.
- **Graph arguments.** A leaf nothing provides becomes a parameter of the
  generated `Graph.start(…)`; the parameter name is the identity, replacing
  qualifiers and `@Provides` modules.
- **ViewModels and Compose.** Generated per-ViewModel adapters, rotation-retained
  by androidx, with unprovidable constructor parameters becoming adapter
  parameters — no assisted-injection concept. Compose gets `remember…` forms and
  `injected<T>()`.
- **The board.** A host-side infinite canvas of the graph, started by every debug
  build, animating each rebuild and offering decision cards that write
  `GraphRules.kt` for you. Optionally augmented with live runtime state pulled
  from a device over `adb`.
- **One Gradle plugin.** `id("com.kitedi")` applies KSP, wires every Kite
  dependency and configures the processor.

### Known limitations

- `Set<I>` collects implementations per module; cross-module contributions are not
  supported yet.
- A graph argument's identity is its parameter name, so unrelated leaves sharing a
  name unify.
- The board's live runtime needs `adb`; there is no Wi-Fi mode.
- Isolated projects is not supported in a multi-module build: collecting each
  module's graph fragment reads other projects' configurations.

[Unreleased]: https://github.com/Kite-Di/kite/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/Kite-Di/kite/releases/tag/v0.1.1
[0.1.0]: https://github.com/Kite-Di/kite/releases/tag/v0.1.0
