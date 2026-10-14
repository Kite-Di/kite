# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
semantic versioning — with the caveat that 0.x makes no stability promise.

## [Unreleased]

### Added

- **`KiteConfig(onError = …)`** sees every failure the running graph throws, just
  before it is thrown — the place to hand it to a crash reporter. What can still fail
  at runtime is down to a lookup before `Graph.start` and a second `Graph.start` with
  different arguments; a test in the runtime pins every other `throw` to the
  compile-time check that rules it out.

- **`Supplies<T>` — a module binds a type it does not own.** Implementing an interface
  is a binding, and this is that rule applied to a type nobody in the project wrote:

  ```kotlin
  // :core:network — okhttp is a dependency of this module and of nothing else
  class Http(
      private val cache: RequestCache,
      private val timeoutMillis: Long = 30_000,
  ) : Supplies<OkHttpClient> {
      override val value: OkHttpClient = OkHttpClient.Builder()
          .callTimeout(timeoutMillis, MILLISECONDS)
          .cache(cache.asOkHttpCache())
          .build()
  }
  ```

  Any module may now take an `OkHttpClient` constructor parameter. `Graph.start` gains
  nothing and the application never learns that the module speaks HTTP — before this,
  a library object could only enter the graph as a graph argument, so the interceptors
  and timeouts of an `:impl` module's client had to be written in `:app`, the one
  module an api/impl split exists to keep unaware of them.

  A supplier is an ordinary binding otherwise: its constructor is inferred, its own
  unprovidable leaves still bubble up, it is a singleton unless decided otherwise, and
  a `@Scoped`/`@Fresh` decision on it carries to the type it supplies. Two suppliers of
  one type is the ambiguity you already know, with the same `@Bind` fix. Values —
  an API key, a timeout — are unchanged: they are still graph arguments, which is the
  right place for something only the application knows.

- **`com.kitedi:test` — the real graph, restarted per test.** Hilt gave every test a
  fresh component and let a test module replace a production one; Kite had neither,
  so an instrumentation test that went through the graph inherited every singleton
  the previous test touched. The plugin now puts a JUnit rule on the unit and
  instrumentation test classpaths:

  ```kotlin
  @get:Rule(order = 0)
  val kite = KiteTestRule { app ->
      replace<TaskRepository>(FakeTaskRepository())
      Graph.start(app, localDataSource = db.taskDao(), dispatcher = Dispatchers.Main, scope = TestScope())
  }
  ```

  The block is the production `Graph.start` call, so graph arguments stay
  compile-checked. `replace` swaps the binding behind one type — the interface moves,
  the implementation class keeps resolving the real one — and a replacement nothing
  in the graph provides fails the start instead of doing nothing. The artifact never
  reaches an application's runtime classpath. See [testing](https://kitedi.com/guide/testing).

### Changed

- **Lookups are generated, so the compiler checks them.** `by injected()` and
  `Kite.get<T>()` named their type inside a function body, where the processor cannot
  see it: a type with no binding, or an Activity-scoped binding asked for from a
  Service, built fine and threw on the device. Every type you may look up now gets a
  generated accessor named after it, the way a ViewModel gets `counterViewModel()`:

  ```kotlin
  private val presenter: FirstPresenter by firstPresenter()   // was: by injected()
  appInitializer().value.runAll()                             // was: Kite.get<AppInitializer>()
  ```

  A type nothing binds has no accessor, and an accessor exists only on receivers that
  have its binding's lifetime — any `Context`, `Fragment` or `View` for an app-wide
  binding, an `Activity` or `Fragment` for an Activity-scoped one, a `Fragment` for a
  Fragment-scoped one. A wrong lookup is an unresolved reference in the IDE. Accessors
  exist for every interface a class is bound to, every supplied type and every
  `@Root` class; a plain class reached only through constructors has none. ViewModel
  adapters follow the same rule, and a ViewModel whose dependencies live in a custom
  scope is a build error rather than an adapter that cannot work.

  **Migrating:** replace `by injected()` with the accessor named after the type, and
  `Kite.get<T>()` with `context.t().value`. The old calls still compile behind
  `@OptIn(UncheckedLookup::class)`, for what an accessor cannot reach — custom scopes,
  until they are typed. `Kite.init` and `Kite.scopeOf` are now `@KiteGeneratedApi`:
  `Graph.start` is the way in.
- **A graph belongs to its `Application`.** Calling `Graph.start` a second time for
  the same `Application` used to return silently with the new arguments thrown away.
  The same arguments again now change nothing; different ones throw. A *different*
  `Application` gets a fresh graph — which is what Robolectric hands every test.

### Fixed

- **The board jar was downloaded when the board would not start, under the
  configuration cache.** 0.1.1 stopped `-Pkite.startBoard=false` and CI builds from
  depending on it, but the `kiteBoard` action still captured the classpath, and the
  configuration cache resolves whatever an action captures — on by default in new
  Gradle 9 projects. Such builds now resolve nothing.
- **Marks shipped their annotation's name.** A marked binding's key was
  `Key(Gateway::class.java, qualifier = "com.example.Stripe")` — a string R8 cannot
  rename, so a release APK carried the original name of every mark while the classes
  around it were obfuscated. Keys now hold the annotation's class,
  `Key(Gateway::class.java, mark = Stripe::class.java)`, like every other part of a
  key, and the metadata a module exports for the others lists them the same way
  (`@ProvidedKeys(marks = [Stripe::class, …])`), release builds included. Registries
  generated by an earlier processor use the old form: rebuild every module with the
  same Kite version (the plugin pins it).
- **A library's annotation could move an implementation off its interface.** Any
  annotation outside a short list of platform packages counted as a mark, so
  `@Serializable class RealRepo : Repo` bound `Repo` only under the mark
  "Serializable" — every plain `Repo` parameter then failed with a missing binding.
  Only annotations the project declares, in any of its modules, are marks now.
- **A ViewModel's optional parameter crashed on the device.** `pageSize: Int = 20`
  with nothing providing an `Int` was resolved anyway by the generated adapter and
  threw `No binding for kotlin.Int`; factories already left such a parameter to its
  default, and adapters now do too.
- **A class reached only through a ViewModel lost its own dependencies.** In
  `FeedViewModel(loader: FeedLoader)`, `FeedLoader(cache: Cache)`, the build reported
  `Cache` as a missing binding: the ViewModel pulled `FeedLoader` in, but nothing
  walked on from there.
- **A `Set<I>` consumed in the application failed when its contributors lived in other
  modules.** "has no implementations" was decided before the other modules'
  contributors were merged in; it is decided after now. An `internal` contributor
  in another module also broke the application's build — the generated code named
  the class, which Kotlin forbids outside its module; it names the public
  `<Type>_Key` handle the owner already generates.
- **Two modules binding one interface crashed at startup.** Modules that cannot see
  each other each claimed the interface; the application kept the first and the
  container threw `Duplicate binding` when it started. The application's build now
  names both modules — see [build errors](https://kitedi.com/guide/errors#one-interface-bound-in-two-modules).
- **A singleton could reach an Activity's binding through a set or an unscoped class.**
  The scope check looked at direct parameters only, so a singleton consuming a
  `Set<Task>` with an activity-scoped element, or a `@Fresh` helper that wanted the
  Activity's `Session`, compiled and failed on the device. What has no scope of its
  own is built in its consumer's scope, and is checked as part of it.
- **Robolectric tests shared one graph.** Every test gets a new `Application`, but the
  graph kept the first test's — its `Context`, its graph arguments, every singleton —
  so a test could pass or fail depending on which ran before it.
- **Test fixtures broke the build of a Kite module.** Inference was skipped for tasks
  whose name contained `UnitTest` or `AndroidTest`, and `kspDebugTestFixturesKotlin`
  contains neither: it generated a second `Graph` that did not compile. Only a
  variant's own sources are inferred now, whatever AGP names the other tasks.
- **A module reachable only through another module's `implementation` could go missing
  from the merged graph without a word.** `implementation` is not transitive, so such a
  module never lands on the application's compile classpath: its registry is absent
  from `MergedRegistry`, the build passes, and the first resolution that needs it dies
  on the device with a message that names the type and cannot name the cause — and
  whose hint is actively wrong:

  ```
  KiteException: No binding for …core.data.MyModelRepository
    hint: implement a project interface of that type, or add @Root(MyModelRepository::class)…
  ```

  The interface *was* implemented, and a `@Root` would not have helped.

  `ClosureValidator` already caught this when the unreachable type was a constructor
  parameter of a binding the application merges — it re-reads those constructors. It
  could not catch a demand that belongs to no binding: a module whose only graph
  content is a ViewModel exports no keys at all, while its generated
  `remember<Name>ViewModel()` adapter resolves whatever that ViewModel asks for.

  Each module now records which modules its graph resolved keys from
  (`@ProvidedKeys.requires`), and the application checks that every one of them also
  reached its merge:

  ```
  e: [ksp] Module :data is missing from the merged graph
    :feature resolves keys from it, and :feature is merged into this application
    :data is not on this application's compile classpath, so nothing registers its
    bindings and resolution would fail at runtime
    hint: `implementation` is not transitive — export it with api(project(":data"))
    from :feature, or depend on :data from this module too.
  ```

  Module paths rather than class references on purpose: the type that would be named is
  exactly the one missing from the classpath being checked, so a `KClass` entry could
  not be read back.

  A recorded demand is not the only way in, and the other way cannot be recorded at all:
  `Kite.get<T>()` and `by injected()` live inside function bodies, which KSP does not
  read, so a module can be needed with nothing able to say so. The build goes by the
  other fact it can see. The plugin compares the application's runtime and compile
  classpaths, keeps the modules of this build that carry a generated registry, and any
  that ship without being merged is an error too:

  ```
  e: [ksp] Module :data has a graph this application does not merge
    its classes ship here — it is on the runtime classpath — while its registry is
    not on the compile classpath, so its bindings are registered nowhere
  ```

  The rule is simply that a graph which ships is part of this graph; "nothing happens to
  resolve from it today" is not a guarantee anyone can hold. Plain modules without a
  registry are nobody's business and are left alone. `demo/viewmodel_merge_app` pins both
  halves — `:viewmodel` names the type in a constructor, `:lookup` only calls `Kite.get`
  inside a function — and is kept apart from `demo/transitive_app`, where the
  binding-shaped case reports the same thing and would mask a regression in either.

- **An app with a dynamic feature could not use Kite at all.** AGP rejects *every*
  external `compileOnly` dependency on a base module that declares a dynamic feature —
  the feature's runtime classpath is derived from the base's, so a compile-only entry
  would be missing at runtime, and AGP cannot know these particular annotations are
  erased long before that matters. The plugin adds the `@Root`/`@Bind`/`@Scoped`
  vocabulary as `compileOnly` on purpose, so the build stopped before compiling
  anything:

  ```
  > Task :app:preDebugBuild FAILED
    The following Android dependencies are set to compileOnly which is not supported:
    -> com.kitedi:rules:0.1.1
  ```

  On such a module the vocabulary now arrives on `implementation` instead. SOURCE
  retention means no annotation reference survives compilation, so R8 deletes the
  classes from the release build — `scripts/check-apk-safety.sh` reads `mapping.txt`
  to prove it, and an unminified debug build carries eight annotation classes, 7 KB.
  Every other module keeps `compileOnly`, where nothing can reach an APK by
  construction rather than by R8.

  Project dependencies are exempt from AGP's rule, which is why no demo in this
  repository could have caught it — `demo/dynamic_app` is the new fixture that does,
  resolving Kite from mavenLocal the way a consumer does, and the compatibility
  workflow builds it on every toolchain row and then checks the release APK.

- **An app whose package starts with `com.google.`, `org.jetbrains.` or `androidx.`
  got no interface bindings at all.** Whether a supertype was the project's own was
  decided by a list of package prefixes, so every class of
  `android/architecture-samples` (`com.google.samples.modularization.*`) was
  classified as third-party: implementing an interface stopped being a binding,
  `@Bind` reported "no implementations of that type in this module" with the
  implementation in the same file tree, and a ViewModel's repository parameter
  silently became a parameter of the generated adapter.

  The build knows the answer exactly, so it is now asked: the Gradle plugin resolves
  the compile classpath filtered to `ProjectComponentIdentifier`s and passes the
  result as `kite.projectClasspath`; a type is the project's own when its package is
  one this compilation declares or one another module of the build produces. Package
  names no longer take part in the decision.

  Fixing this also closed a second symptom that looked separate: a module whose
  classes are consumed only from *other* modules — the usual `:data` module holding a
  repository interface and its single implementation — exported an empty registry, and
  needed a `@Root` that the documentation says it should not need. It no longer does.

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

### Added

- **`kiteBoardStop`.** The board server outlives the build that started it, so
  ending it was a matter of finding the process yourself. It now records its pid
  next to its port and the task stops it — from any module, in any build of the
  repository.

### Fixed

- **The board says whose graph it is serving.** With more than one app in a
  repository the first build to start the server owned it, and every later build
  printed a link to somebody else's graph. It now names both apps and offers the
  two ways out.
- **The board auto-starts from an included build.** `buildLike` was read from the
  included build's own `startParameter`, which is empty in a composite: the task
  announced a link and started nothing for every demo but the root one.
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
