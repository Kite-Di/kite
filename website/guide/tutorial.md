# Tutorial

We are going to build the dependency graph of a small profile screen, one class at
a time. Nothing here is a toy shortcut — this is what a real feature looks like.

The point of going slowly is that most steps involve **writing no DI code at
all**. When a step does need something, we will let the build error tell us, so
you learn the errors at the same time as the features.

You will end up with: a network layer, a repository behind an interface, a
ViewModel, a decision between two analytics implementations, an Activity-scoped
class, and a board showing all of it.

::: tip
If you would rather read the finished thing, the repository's demo app under
[`demo/modular_app`](https://github.com/Kite-Di/kite/tree/main/demo/modular_app)
is exactly this, at production scale.
:::

## Step 0 — set up the project

Create an Android project as usual, then follow the three setup steps in
[getting started](/guide/getting-started): plugin repositories in
`settings.gradle.kts`, KSP on the root build classpath, and `id("com.kitedi")` on
your app module.

Add the startup call, empty for now:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
```

Build. Nothing happens, because there is no graph yet. Let's make one.

## Step 1 — your first binding is just a class

```kotlin
// ProfileApi.kt
class ProfileApi {
    fun fetchName(): String = "Ada Lovelace"
}
```

That is a binding. Not because of an annotation — because it is a class you own,
and in a moment something is going to take it as a constructor parameter.

```kotlin
// ProfileRepository.kt
class ProfileRepository(private val api: ProfileApi) {
    fun userName(): String = api.fetchName()
}
```

Two bindings now. `ProfileRepository` depends on `ProfileApi`; the processor read
that from the constructor. Both are shared instances app-wide, because
[everything in the graph is a singleton by default](/guide/lifetimes).

**You have written zero lines of DI.**

## Step 2 — put the repository behind an interface

Real features depend on contracts, not implementations:

```kotlin
// ProfileRepository.kt
interface ProfileRepository {
    fun userName(): String
}

class NetworkProfileRepository(private val api: ProfileApi) : ProfileRepository {
    override fun userName(): String = api.fetchName()
}
```

`NetworkProfileRepository` is the sole implementation of a project interface, so
it *is* the binding for `ProfileRepository`. Anything asking for the interface
gets it.

Again: no annotation, no module, no registration. This is the single most
important idea in Kite — [implementing the interface is the
binding](/guide/inference#implementing-an-interface-is-the-binding).

## Step 3 — a value the graph cannot invent

Our API needs a base URL and a timeout. Just declare them:

```kotlin
class ProfileApi(
    private val baseUrl: String,
    private val timeoutMillis: Long,
) {
    fun fetchName(): String = "Ada Lovelace"
}
```

Build, and look at what the generated façade became:

```kotlin
Graph.start(this, baseUrl = "https://api.example.com", timeoutMillis = 5_000)
```

Nothing provides `String` or `Long`, so they bubbled up as
[graph arguments](/guide/inference#graph-arguments) — parameters of your startup
call. **The parameter name is the identity.** Any other class anywhere in the app
that takes a `timeoutMillis: Long` gets this same value.

This is the replacement for `@Named` qualifiers *and* for a module full of
`@Provides` functions: construction of things you don't own is ordinary Kotlin at
an ordinary call site.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(
            this,
            baseUrl = BuildConfig.API_URL,
            timeoutMillis = 5_000,
        )
    }
}
```

## Step 4 — inject into an Activity

An Activity is constructed by Android, not by the graph, so this is the first
place we actually ask for something:

```kotlin
class ProfileActivity : AppCompatActivity() {
    private val repository: ProfileRepository by injected()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = repository.userName()
    }
}
```

This works, because `ProfileRepository` is an interface and the graph already
binds it.

Now try the same with a plain class — say you wanted `ProfileApi` directly:

```kotlin
private val api: ProfileApi by injected()
```

Still fine here, because `NetworkProfileRepository` takes `ProfileApi` in its
constructor, so the processor already saw it. But if nothing in your code
constructed it, the build would say:

```
Unused binding: ProfileApi (.../ProfileApi.kt:3) is never injected.
Add @Root(ProfileApi::class) if it is resolved dynamically, or delete the class.
```

That is the warning telling you the truth: `by injected()` is a *runtime* lookup,
invisible to a processor reading constructors. Create the module's rules file and
declare it:

```kotlin
// GraphRules.kt
import com.kite.di.rules.Root

@Root(ProfileApi::class)
private object GraphRules
```

This file is where every decision in this module will live. It is committed, and
[none of it reaches the APK](/guide/rules#why-class-references-and-not-strings).

## Step 5 — a ViewModel

Kite sees the `ViewModel` supertype and generates an adapter named after the
class. Nothing to configure:

```kotlin
class ProfileViewModel(
    private val repository: ProfileRepository,
) : ViewModel() {
    val name: String get() = repository.userName()
}
```

```kotlin
class ProfileActivity : AppCompatActivity() {
    private val profile: ProfileViewModel by profileViewModel()   // generated
}
```

androidx's `ViewModelStore` owns the instance, so it survives rotation and gets
`onCleared` as usual — the graph only fills the constructor.

Need a runtime argument? Declare it, and it becomes a parameter of the adapter —
there is no assisted injection to learn:

```kotlin
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val userId: String,
    private val saved: SavedStateHandle,
) : ViewModel()
```

```kotlin
private val profile by profileViewModel(userId = intent.getStringExtra("id")!!)
```

## Step 6 — your first real decision

Add analytics, with two implementations — one for debug, one for release:

```kotlin
interface Analytics { fun track(event: String) }

class FirebaseAnalytics(private val api: ProfileApi) : Analytics { … }
class LogcatAnalytics : Analytics { … }
```

Build, and it stops:

```
Duplicate binding for com.example.Analytics:
  1) FirebaseAnalytics (.../FirebaseAnalytics.kt:5)
  2) LogcatAnalytics (.../LogcatAnalytics.kt:3)
  hint: keep one implementation, or choose with a @Bind rule (GraphRules.kt).
```

This is the one case the code genuinely cannot answer — both classes are valid
implementations, and only you know which one this app wants. So it becomes a
line in the rules file:

```kotlin
// GraphRules.kt
import com.kite.di.rules.Bind
import com.kite.di.rules.Root

@Root(ProfileApi::class)
@Bind(Analytics::class, to = FirebaseAnalytics::class)
private object GraphRules
```

`Analytics::class` and `FirebaseAnalytics::class` are compile-checked references.
Rename either class and the refactoring updates this file; delete one and the
file stops compiling. It cannot go stale.

::: tip The board writes this for you
Leave the build broken and open the board — the ambiguity shows up as a decision
card, and clicking your choice writes exactly the line above into the right
module's rules file. [See the board](/guide/board)
:::

## Step 7 — a shorter lifetime, and the error it prevents

Some state should live as long as the screen and no longer:

```kotlin
class ProfileDraft {
    var text: String = ""
}
```

```kotlin
// GraphRules.kt
@Scoped(ProfileDraft::class, "activity")
```

Activity scope survives rotation and is cleared when the Activity genuinely
finishes — you open and close nothing.

Now make a mistake on purpose. Let a singleton depend on it:

```kotlin
class DraftUploader(private val draft: ProfileDraft)   // singleton by default
```

```
Scope violation: singleton DraftUploader depends on activity ProfileDraft
  singleton DraftUploader (.../DraftUploader.kt:1)
  depends on activity ProfileDraft (.../ProfileDraft.kt:1) via constructor param 'draft' (.../DraftUploader.kt:1)
  hint: a longer-lived binding cannot depend on a shorter-lived one — give DraftUploader an
  equal-or-shorter scope (@Scoped in GraphRules.kt), or mark it @Fresh (a new instance
  per consumer, no cache).
```

A singleton holding Activity-scoped state is a leak, and it is a class of bug
that normally shows up as a mysterious crash after rotation. Here it is a compile
error with both file positions. Give `DraftUploader` the same scope, and move on.

## Step 8 — look at what you built

Run a debug build:

```
> Task :app:kiteBoard

  Dependency board: http://localhost:8394  (starting)
```

Open it. Every class from this tutorial is a node; `baseUrl` and `timeoutMillis`
are external nodes at the edge; `ProfileViewModel` is an entry point. Click
`ProfileRepository` to see that it is provided by `NetworkProfileRepository` and
injected into `ProfileViewModel`, with file and line for each.

Leave the board open and edit a class in Android Studio. The next build animates
the difference.

[More about the board →](/guide/board)

## Step 9 — split it into modules

When the screen becomes a feature, move it:

- `:feature:profile:api` — the `ProfileRepository` interface. **No Kite plugin**:
  nothing in it is injectable.
- `:feature:profile:impl` — `NetworkProfileRepository`, `ProfileApi`,
  `ProfileViewModel`, and this module's `GraphRules.kt`. Kite plugin applied.
- `:app` — the Activity and `Graph.start`.

Nothing else changes. `NetworkProfileRepository` in `:impl` binds the interface
from `:api` and is injectable in `:app` with no ceremony, because
[interface implementations travel free](/guide/multi-module#interface-implementations-travel-free).
The `@Bind` for `Analytics` stays in the module that owns those classes.

The board now draws each module as its own labeled container.

[More about multi-module apps →](/guide/multi-module)

## What you never wrote

No `@Inject` constructors. No `@Module`, no `@Provides`, no `@Binds`. No
component, no subcomponent, no `@InstallIn`. No qualifiers. No factories for
runtime arguments.

What you *did* write: three lines in one `GraphRules.kt`, each of them a genuine
product decision that no compiler could have made for you.

## Next

- [How the graph is inferred](/guide/inference) — the rules, stated precisely
- [Build errors](/guide/errors) — the rest of the messages
- [Patterns](/guide/patterns) — `Lazy`, optional dependencies, plugin sets
- [Limitations](/guide/limitations) — what to know before adopting
