# Lifetimes

**Everything is a singleton.** One shared instance per app process, created on
first use. Implementation, presenter, helper, repository — if it is in the graph,
it is shared, and you never think about lifetime until you need a different one.

This is the opposite of the usual default, and it is deliberate: in a real app
the overwhelming majority of graph classes are stateless collaborators that
nobody wants a second copy of. Making the common case free means the rare case
gets to be explicit.

## The table

| Lifetime | One instance per | Cleared |
|---|---|---|
| singleton *(the default — nothing to write)* | app process | never |
| `@Fresh` on the class *(or `@Scoped(X::class, "none")`)* | injection | — |
| `"activity"` | Activity — **survives rotation** | when the Activity truly finishes |
| `"fragment"` | Fragment instance | when the Fragment is destroyed |
| custom `"<Name>", level = N` | your custom scope | when you `close()` it |

## A new instance every time — `@Fresh`

The rare opposite of a singleton is worth seeing right at the class, so the
decision lives there rather than in the rules file:

```kotlin
import com.kite.di.rules.Fresh

@Fresh   // a new instance every time it is injected — never shared
class DefaultGreetingUseCase(private val repository: UserRepository) : GreetingUseCase
```

## Android lifetimes — `@Scoped`

Everything else is a decision in [`GraphRules.kt`](/guide/rules):

```kotlin
@Scoped(SessionState::class, "activity")
```

Scopes open and close by themselves with the Android lifecycle — there is no
component to build, install or remember to close. `"activity"` survives rotation
and is cleared when the Activity genuinely finishes.

One class gets one lifetime decision; deciding it twice is a build error.

## Direction is enforced

A longer-lived binding may not depend on a shorter-lived one. A singleton that
takes an Activity-scoped dependency would outlive it and leak it, so the build
stops with both file:line locations and a hint:

```
Scope violation: singleton AppCache depends on activity SessionState
```

This is a compile error, not a runtime surprise. The full message is in
[build errors](/guide/errors#scope-violation).

## Custom scopes

For a lifetime the built-ins don't cover — a login session, a checkout flow —
declare it with its own level. Levels order lifetimes, so two scopes sharing a
level is a build error; the built-ins use 0, 1 and 2:

```kotlin
@Scoped(SessionCache::class, "Session", level = 10)
```

You open and close a custom scope yourself, and resolve through the handle (it
also works as `owner` in `Kite.get`):

```kotlin
val session = Kite.openScope(ScopeId("session"), name = "Session", level = 10)
val cache: SessionCache = session.get()
…
session.close()   // drops the scope's instances (ScopeHandle is a Closeable)
```
