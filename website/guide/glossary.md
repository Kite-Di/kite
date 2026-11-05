# Glossary

The words these docs use, in one place. Where Dagger or Hilt has a word for the same
thing, it is under the entry. *Module* always means a Gradle module.

## Accessor

A function the processor generates for each type you may ask the graph for, named
after it: `Analytics` gets `analytics()`. It returns a `Lazy`, so it reads
`private val analytics by analytics()`. Every interface a class is bound to, every
supplied type and every [root](#root) gets one; a [marked](#mark) binding carries its
mark in the name — `stripeGateway()`.

The compiler checks it. A type with no binding has no accessor, and an accessor exists
only on receivers that have the binding's [lifetime](#lifetime) — an Activity-scoped
binding on Activities and Fragments, never on a Service.
[Injecting](/guide/injecting#generated-accessors).

*Dagger/Hilt:* `@AndroidEntryPoint` + `@Inject lateinit var`, `EntryPointAccessors`.

## Adapter

The generated function that hands a ViewModel to its host: `counterViewModel()` for
`CounterViewModel`, `rememberCounterViewModel()` in Compose. A constructor parameter
the graph cannot provide becomes a parameter of the adapter.
[ViewModels & Compose](/guide/viewmodels).

*Dagger/Hilt:* `@HiltViewModel` + `by viewModels()`; `@AssistedInject` for runtime
arguments.

## Binding

How the graph gets an instance of a type. You never write one: a class you own is a
binding as soon as something takes it as a constructor parameter, the sole
implementation of a project interface is bound to that interface, and a
[supplier](#supplier) binds the type it supplies.
[How the graph is inferred](/guide/inference).

*Dagger/Hilt:* `@Inject constructor`, `@Binds`, `@Provides`.

## Board

A page in your browser that draws the [graph](#graph): every binding, where it is
provided and where it is injected, with file and line. Every debug build prints its
link and starts it. It runs on your computer only; nothing of it ships in the app.
[The board](/guide/board).

## Decision

A fact the code cannot say, and the only thing you write down: which implementation an
interface gets (`@Bind`), a lifetime other than singleton (`@Scoped`, `@Fresh`), a
consumer the compiler cannot see (`@Root`). Decisions live in the
[rules file](#rules-file) of the module that owns the class.
[Decisions — GraphRules.kt](/guide/rules).

*Dagger/Hilt:* what a `@Module` holds, minus everything inference already knows.

## Decision card

A card on the [board](#board) for a build that stopped on a choice only you can make —
two implementations of one interface. Picking one writes the `@Bind` line into the
rules file of the module the choice belongs to.

## Entry point

A class the graph fills but does not own. A ViewModel is one: androidx's
`ViewModelStore` owns the instance, so graph lifetimes never apply to it, and you reach
it through its [adapter](#adapter), not an accessor.

*Dagger/Hilt:* Hilt uses the word for `@EntryPoint` interfaces; Kite has none of those.

## Export

What a module offers other modules: its interface implementations, the types its
suppliers supply, its [roots](#root), and everything their constructors pull in. A
plain class only other modules use is not exported until the owning module adds a
`@Root` for it. [Multi-module apps](/guide/multi-module#what-crosses-a-module-boundary).

## Fresh

`@Fresh` on a class: a new instance every time it is injected, never shared. The one
decision written on the class itself, because "never shared" is part of its contract.
`@Scoped(X::class, "none")` says the same in the rules file.
[Lifetimes](/guide/lifetimes#a-new-instance-every-time-fresh).

*Dagger/Hilt:* an unscoped binding — Dagger's default.

## Graph

Every binding in the app, and who needs whom. Kite works it out from your source code
while the app is built, checks it, and generates the Kotlin that creates the objects:
on the device they are made by plain constructor calls, not reflection. Each module
works out its own part; the application [merges](#merge) them.

*Dagger/Hilt:* a component's graph — without the component.

## Graph argument

A value nothing in the graph can make — an API key, a timeout. Declare it as a
constructor parameter anyway; it becomes a parameter of [`Graph.start`](#graph-start).
Its identity is the parameter name: `apiKey` in three modules is one argument, filled
once. [Graph arguments](/guide/inference#graph-arguments).

*Dagger/Hilt:* `@BindsInstance`, or a `@Provides` of a `@Named` value.

## `Graph.start`

The generated function your `Application` calls once, in `onCreate`. Its parameters are
the app's [graph arguments](#graph-argument).
[Getting started](/guide/getting-started#_5-start-the-graph).

*Dagger/Hilt:* `@HiltAndroidApp`.

## Inference

How Kite works out the graph: the processor reads declarations — constructors,
supertypes, the rules file — and infers the bindings. It reads declarations, not
function bodies, which is why a lookup at runtime needs a [root](#root).
[How the graph is inferred](/guide/inference).

## KiteTestRule

The JUnit rule from `com.kitedi:test`, which the plugin puts on your test classpaths.
It restarts the graph around every test; inside it, `replace<T>(fake)` swaps a binding.
[Testing](/guide/testing#kitetestrule).

*Dagger/Hilt:* `HiltAndroidRule`, `@BindValue`, `@TestInstallIn`.

## Lifetime

How long one instance is shared. Singleton is the default; `"activity"`, `"fragment"`,
a [scope](#scope) of your own and `@Fresh` are decisions. A longer-lived binding may
not depend on a shorter-lived one — the build checks it. In code a lifetime is a scope,
`@Scoped(X::class, "activity")`; in prose these docs say lifetime.
[Lifetimes](/guide/lifetimes).

*Dagger/Hilt:* a scope annotation plus the component that holds it.

## Lookup by type

`Kite.get<T>()`, `by injected()` and Compose's `injected<T>()`: asking for a type at
runtime, from a function body. The compiler cannot check it — a type with no binding,
or a lifetime the caller does not have, throws there. Use an [accessor](#accessor)
where one exists. [Lookups by type](/guide/injecting#lookups-by-type).

*Dagger/Hilt:* `EntryPointAccessors.fromApplication(…)`.

## Mark

Your own annotation, on an implementation and on the parameter that wants it, to choose
between implementations at the call site. Kite defines no annotation for it; any
annotation your project declares works. When the choice is the app's and not the
consumer's, use `@Bind` instead.
[Marks](/guide/patterns#choosing-between-implementations-marks).

*Dagger/Hilt:* `@Qualifier`, `@Named`.

## Merge

The application combining every module's graph into one. Only the application sees
every module, so it composes each `Set<I>` and `Graph.start` — and it refuses to build
when a module of the graph never reaches it, because `implementation` is not
transitive. [Multi-module apps](/guide/multi-module#what-is-still-checked-across-modules).

## Module

A Gradle module, always. Kite has nothing like a Dagger `@Module`: what one held is
inferred, or is a [decision](#decision). Apply the plugin in every module that has
graph classes.

## Root

`@Root(X::class)` in a rules file: a consumer of a plain class that the compiler cannot
see — a lookup at runtime, or another module. It gives the class an
[accessor](#accessor) and [exports](#export) it. A class behind an interface never
needs one. [`@Root`](/guide/rules#root).

## Rules file

The one optional file per module that holds its [decisions](#decision), as annotations
on a private object. Any name works; `GraphRules.kt` by convention. Its annotations are
`SOURCE`-retention and `compileOnly`, so nothing in it reaches the APK.
[Decisions — GraphRules.kt](/guide/rules).

## Scope

A [lifetime](#lifetime), as code names it. A *scope of your own* is one you name —
`@Scoped(SessionCart::class, "Session")` — and Kite generates `openSession()` and
`closeSession()` for it. One is open at a time; opening it again starts over.
[Custom scopes](/guide/lifetimes#custom-scopes).

*Dagger/Hilt:* a custom `@Scope` with its own component.

## Set

Inject `Set<I>` to get every implementation of `I`, from every module on the build
path. Adding an implementation touches no other file.
[The plugin pattern](/guide/patterns#the-plugin-pattern-set-i).

*Dagger/Hilt:* `@IntoSet`, multibindings.

## Singleton

The default lifetime: one instance per app process, created on first use. There is
nothing to write — `@Scoped(X::class, "singleton")` earns a warning.

*Dagger/Hilt:* `@Singleton`, which there you opt in to.

## Supplier

A class implementing `Supplies<T>`: it binds a type you don't own — `OkHttpClient`,
`Retrofit` — in the module where that library belongs, and any module may then take a
`T` parameter. A value, like an API key, stays a [graph argument](#graph-argument).
[Types you don't own](/guide/patterns#types-you-don-t-own-supplies-t).

*Dagger/Hilt:* `@Provides` for a library type.

## Words Kite does not have

Component, subcomponent, `@Inject`, `@InstallIn`, assisted injection. What each one did
is in [coming from Hilt or Dagger](/guide/migration).
