# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
semantic versioning — with the caveat that 0.x makes no stability promise.

## [Unreleased]

## [0.1.1] — 2026-09-23

### Added

- **`Set<I>` collects across modules.** Implementations contribute from wherever
  they are declared, and the application composes the set — it is the one
  compilation that sees every contributor. The aggregator itself may live
  anywhere, including a module the contributors depend on, which is where the
  contracts usually are. Adding a feature module is the whole registration; no
  module lists the others, and nothing is written down. `demo/plugin_app` is
  built on this.
- **Marks choose between implementations.** Put your own annotation on an
  implementation and the same one on the parameter that wants it:

  ```kotlin
  @Stripe class StripeGateway : Gateway          // in one module
  @PayPal class PayPalGateway : Gateway          // in another

  class Checkout(@Stripe private val gateway: Gateway)
  ```

  Both sides are read in their own module; what ties them together is the key
  they produce. Kite defines no annotation for this and requires no meta-
  annotation — any annotation you own works, platform ones never do, and a mark
  nothing provides falls back to ordinary resolution. A marked implementation
  keeps its own class key, so importing the module and depending on the class
  directly still works.

### Fixed

- **Several modules implementing one interface no longer collide at startup.**
  Each module believed it held the only implementation and claimed the
  interface's key; merged, they threw `Duplicate binding`. A marked
  implementation now claims its own key, and an interface consumed as a set is
  not claimed singularly at all.
- **An application module that owns no injectable class still gets its `Graph`.**
  The processor returned early when a compilation had nothing to infer, which
  left a thin shell — the natural shape for an app whose features live in
  modules — with no generated façade at all.
- **`internal` implementations compile.** The generated factory was public and
  exposed an internal return type, so marking any binding `internal` broke the
  build. The factory now carries the same visibility, and a public `<Type>_Key`
  handle is generated beside it so other modules can name the binding without
  naming the class.
- **A module missing from the merged graph is a build error, not a crash on the
  device.** Gradle's `implementation` is not transitive, so a module reached only
  through another module's `implementation` never lands on the app's compile
  classpath: its registry was silently absent from `MergedRegistry`, the build
  succeeded, and the first resolution that needed it threw at runtime. The
  aggregate pass now re-reads the constructors of the bindings it merges and
  refuses to build when one of them cannot be constructed here.
- **The configuration cache works.** `kiteBoard` held a detached `Configuration`,
  which Gradle cannot serialize, so every consumer's debug build fell back to no
  configuration cache — on by default in new Gradle 9 projects.
- **The board jar is no longer downloaded when the board will not start.** The
  task depended on the resolved classpath unconditionally, so even
  `-Pkite.startBoard=false` and CI builds fetched it.
- The plugin reads its Gradle properties through `providers.gradleProperty`
  rather than `findProperty`, which searched parent projects and is forbidden
  under isolated projects.
- **AGP 8 works.** The plugin read `applicationId` through a typed call on
  `ApplicationExtension`, and AGP 9 dropped the type parameters from
  `CommonExtension`, so the call linked against one major failed on the other.
  It now comes from the variant API, which is stable across both, and AGP 8.13.2
  is a row in the compatibility matrix rather than a known break.
- **The board connects to debug builds that set an `applicationIdSuffix`.** The
  id stamped into `graph.json` came from `defaultConfig`, so it missed the
  suffix and never matched what the running app reports over adb. It is now the
  variant's own id.

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
