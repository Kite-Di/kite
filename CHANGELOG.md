# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
semantic versioning — with the caveat that 0.x makes no stability promise.

## [Unreleased]

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

[Unreleased]: https://github.com/Kite-Di/kite/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Kite-Di/kite/releases/tag/v0.1.0
