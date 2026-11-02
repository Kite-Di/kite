# Limitations

What Kite does not do yet, and what it deliberately does not do. Knowing both
before you adopt it is worth more than discovering them later.

## Version 0.1.1 is not a stable API

The generated surface, the rule annotations and the board protocol may all change
between 0.x releases. Pin an exact version.

## One open instance per scope of your own

`openSession()` opens *the* Session; opening it again replaces it. Two sessions open
side by side are not a thing Kite models — keep such state in a class you own.

## A graph argument's identity is its name

Two leaves called `timeoutMillis` are the *same* argument and get one value —
which is the feature that replaces `@Named`, and also the sharp edge. Two
unrelated `String` leaves that happen to share a parameter name will unify. Give
arguments names specific enough to be globally unique (`profileApiKey`, not
`key`).

## Runtime lookups need a `@Root`

The processor reads constructors. A *plain* class looked up at runtime has no
constructor parameter pointing at it, so it gets no [generated accessor](/guide/injecting#generated-accessors)
until you add `@Root(Foo::class)` to the owning module's
[`GraphRules.kt`](/guide/rules) — the missing accessor is the compiler telling you.
A class behind an interface never needs this.

## Three failures stay at runtime

The build cannot see the order your code runs in, so a lookup before `Graph.start`,
a second `Graph.start` with different arguments and a lookup into a scope of your own
while none is open still throw. `KiteConfig(onError = …)` sees them first. Lookups by
type (`Kite.get`, `by injected()`) are checked when they run, by definition. See
[injecting](/guide/injecting#when-something-can-still-fail).

The same applies to a plain class exported to another module — see
[multi-module apps](/guide/multi-module#exporting-a-plain-class-is-a-decision).

## The board's live runtime needs adb

Runtime facts come from a device over `adb` with a forwarded port. There is no
Wi-Fi mode. Without adb, without a device, or with an app built without the
inspector, the board still works — it shows the static graph and nothing else.

## Toolchain is not loose

KSP is versioned against the exact Kotlin compiler, so Kite tracks specific
Kotlin and KSP versions rather than a range. `minSdk` is 23 and the JVM target is
11. See the [requirements](/guide/getting-started#requirements).

## Things that are missing on purpose

These are not gaps — the concept does not exist because it has nothing to do:

- **Assisted injection.** An unprovidable ViewModel parameter simply becomes a
  parameter of the generated adapter. No `@AssistedInject`, no factory interface.
  See [ViewModels](/guide/viewmodels#runtime-arguments-need-no-assisted-injection).
- **Modules.** A type you don't own is bound by a class that implements
  `Supplies<T>` — a node of the graph with its own dependencies and its own
  lifetime, not a namespace of factory functions to install. See
  [patterns](/guide/patterns#types-you-don-t-own-supplies-t).
- **Qualifier annotations.** A graph argument's identity is its parameter name,
  and choosing between implementations is a
  [mark](/guide/patterns#choosing-between-implementations-marks) — your own
  annotation on both sides, with nothing to apply it to.
- **Components and subcomponents.** Scopes open and close with the Android
  lifecycle; there is nothing to build or install.
