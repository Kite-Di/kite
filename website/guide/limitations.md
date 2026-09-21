# Limitations

What Kite does not do yet, and what it deliberately does not do. Knowing both
before you adopt it is worth more than discovering them later.

## Version 0.1.1 is not a stable API

The generated surface, the rule annotations and the board protocol may all change
between 0.x releases. Pin an exact version.

## `Set<I>` collects per module

Implementations of an interface aggregate into a `Set<I>` **within the module
that injects it**. A set injected in `:app` does not currently pick up
implementations declared in `:feature:orders:impl`.

Cross-module contributions are on the roadmap. Until then, a plugin-style
collection whose implementations live in feature modules needs the set to be
assembled where those modules are visible.

## A graph argument's identity is its name

Two leaves called `timeoutMillis` are the *same* argument and get one value —
which is the feature that replaces `@Named`, and also the sharp edge. Two
unrelated `String` leaves that happen to share a parameter name will unify. Give
arguments names specific enough to be globally unique (`profileApiKey`, not
`key`).

## Runtime lookups need a `@Root`

The processor reads constructors. A *plain* class resolved only through
`by injected()` or `Kite.get<T>()` has no constructor parameter pointing at it,
so it needs `@Root(Foo::class)` in the owning module's
[`GraphRules.kt`](/guide/rules). A class behind an interface never needs this.

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
- **Modules and `@Provides`.** Types you don't own become
  [graph arguments](/guide/inference#graph-arguments); their construction is
  ordinary code at the `Graph.start` call site.
- **Qualifiers.** The parameter name is the identity.
- **Components and subcomponents.** Scopes open and close with the Android
  lifecycle; there is nothing to build or install.
