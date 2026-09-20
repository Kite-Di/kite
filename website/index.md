---
layout: home

hero:
  name: Kite
  text: The graph is inferred, not declared
  tagline: Dependency injection for Android with no annotations and no modules. You write plain classes; the KSP processor reads what the code already says, and every Dagger-style guarantee is checked at compile time.
  image:
    src: /img/mascot.png
    alt: Kite
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: Follow the tutorial
      link: /guide/tutorial
    - theme: alt
      text: GitHub
      link: https://github.com/Kite-Di/kite

features:
  - title: No annotations on your classes
    details: Implementing an interface is the binding. A constructor parameter is a dependency. There is no @Inject, no @Module, no @Provides — and nothing to keep in sync with the code.
    link: /guide/inference
    linkText: How inference works
  - title: Only decisions are written down
    details: The handful of things code cannot express — which of two implementations, a non-default lifetime — live in one committed file per module, as compile-checked class references that refactorings keep current.
    link: /guide/rules
    linkText: GraphRules.kt
  - title: A board that shows the graph
    details: Every debug build opens an infinite canvas of your dependency graph and animates what changed. Click any node to see where it is provided from and where it is injected.
    link: /guide/board
    linkText: See the board
  - title: Nothing ships
    details: The graph, the board and the decision vocabulary are host-side build artifacts. No APK ever contains a graph, a server, or the INTERNET permission.
    link: /guide/board#nothing-ships
    linkText: What stays on your machine
---

## The whole idea, in one file

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(api: Api, db: Db) : UserRepo   // that's it. no annotation.
```

`RealUserRepo` is the sole implementation of a project interface, so it *is* the
binding for `UserRepo`. Its constructor parameters are its dependencies. It is one
shared instance app-wide, because [everything in the graph is a singleton by
default](/guide/lifetimes). It is a node on [the board](/guide/board).

Nothing above is a framework concept you had to learn. That is the point.

![The Kite board showing the demo app's graph](/img/board-overview.png)

## Install

```kotlin
// build.gradle.kts of every module with injectable classes
plugins {
    alias(libs.plugins.android.application)
    id("com.kitedi") version "0.1.0"
}
```

One plugin id replaces the KSP block, the processor dependency and the runtime
dependency in every module. Two more lines go in `settings.gradle.kts` and the root
build file once. [Full setup →](/guide/getting-started)
