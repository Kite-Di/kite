# How the graph is inferred

Other frameworks ask you to declare the graph twice: once as code, once as
annotations or modules that describe that code. Kite reads the code.

Three rules cover almost everything.

## A class you own is a binding

If anything takes it as a constructor parameter, it is in the graph. Its own
constructor parameters are its dependencies:

```kotlin
class Analytics(private val http: HttpClient)
```

Nothing to annotate, nothing to register. Delete the last consumer and the
binding leaves the graph with it.

## Implementing an interface *is* the binding

The sole implementation of a project interface is bound to it:

```kotlin
interface UserRepo { fun userName(): String }

class RealUserRepo(api: Api) : UserRepo        // UserRepo → RealUserRepo, inferred
```

Write a second implementation and the build stops — that is the one case where
the code is genuinely ambiguous and only you know the answer. The error hands you
the exact line to paste into [`GraphRules.kt`](/guide/rules):

```kotlin
@Bind(UserRepo::class, to = RealUserRepo::class)
```

The board shows the same thing as a clickable decision card that writes the line
for you.

## Graph arguments

A type you don't own — OkHttp, a `String`, a timeout — has no project class to
infer from. Declare the parameter anyway. Whatever nothing provides becomes a
**graph argument**, and the generated façade grows a parameter for it:

```kotlin
class HttpClient(cache: RequestCache, timeoutMillis: Long)   // Long? nothing provides it…
```

```kotlin
Graph.start(app, timeoutMillis = 5_000)
```

**The parameter name is the identity.** Two leaves called `timeoutMillis` in
different modules are the same argument and are filled once. This is what
replaces `@Named`/`@Qualifier` and the module-with-`@Provides` pattern in one
move: construction logic for library types is ordinary code, written where
ordinary code goes — before or at the `Graph.start` call site.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(
            this,
            apiKey = BuildConfig.API_KEY,
            timeoutMillis = 5_000,
        )
    }
}
```

## What inference cannot see

Two things are invisible to a compiler reading constructors:

**Runtime lookups.** A plain class resolved only through `by injected()` or
`Kite.get<T>()` has no constructor parameter pointing at it, so nothing pulls it
into the graph. Declare it a root:

```kotlin
@Root(LegacyTracker::class)
```

A class behind an interface needs no root — the interface binding already
included it.

**Cross-module consumers.** A plain class used only by *other* modules is not in
its own module's inferred graph. Same fix, same reason, and it is covered in
[multi-module apps](/guide/multi-module).

## Everything is checked

Inference is not guesswork that fails at runtime. Missing bindings, ambiguity,
duplicates, cycles and scope violations are all build errors with the file and
line of both sides. See [build errors](/guide/errors).
