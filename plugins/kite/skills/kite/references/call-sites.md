# Call sites

<https://kitedi.com/guide/injecting> · <https://kitedi.com/guide/viewmodels>

Most of the time there is no call site: a class Kite creates takes its
dependencies as constructor parameters, and that is the whole story. This file is
for the places **Android** constructs the object.

| Where you are | How you inject |
|---|---|
| A class Kite creates | declare constructor parameters — nothing else |
| Activity / Fragment | generated accessor: `private val presenter: Presenter by presenter()` |
| ViewModel | generated adapter: `private val counter by counterViewModel()` |
| `@Composable` | `val analytics by LocalContext.current.analytics()`, `rememberCounterViewModel()` |
| Custom `View` | `private val analytics: Analytics by analytics()` — resolves in the host Activity's scope |
| Service / BroadcastReceiver / Application | `val analytics by context.analytics()` — app-wide bindings only |

## Activities and Fragments

```kotlin
class FirstFragment : Fragment() {
    private val presenter: FirstPresenter by firstPresenter()
}
```

The accessor is generated, named after the type, and checked by the compiler: it
does not exist when nothing binds the type, and exists only on receivers that have
the binding's scope — an Activity-scoped binding on Activities and Fragments, a
Fragment-scoped one on Fragments, an app-wide one on any `Context`, `Fragment` or
`View`. Import it from the type's package (`com.example.ui.firstPresenter`); a
supplied type's accessor sits in its supplier's package.

**The `@Root` check.** Accessors exist for interfaces a class is bound to, supplied
types and `@Root` classes. If the accessor you need is unresolved:

- the type is behind a project interface → use the interface's accessor;
- a plain class → add `@Root(FirstPresenter::class)` to the module's
  `GraphRules.kt`.

Putting the class behind an interface instead of writing the root is usually the
better change, and it is what the demo app does — its `:core` modules have no
`@Root` lines at all.

## ViewModels

A ViewModel is an entry point, not an ordinary binding: androidx's
`ViewModelStore` owns the instance, so rotation retention and `onCleared` behave
exactly as they always did, and graph scopes never apply. The processor sees the
`ViewModel` supertype and generates a `<name>ViewModel()` adapter.

```kotlin
class CounterViewModel(private val analytics: Analytics) : ViewModel()

class MainActivity : AppCompatActivity() {
    private val counter: CounterViewModel by counterViewModel()   // generated
}
```

**Runtime arguments — no assisted injection.** A constructor parameter the graph
cannot provide becomes a parameter of the generated adapter. `SavedStateHandle`
is supplied automatically.

```kotlin
class CheckoutViewModel(
    flow: CheckoutFlow,
    orderId: String,
    saved: SavedStateHandle,
) : ViewModel()

// in the fragment:
private val vm by checkoutViewModel(orderId = args.orderId)
```

`@AssistedInject`, a factory interface and `@AssistedFactory` all translate to
nothing — delete them and pass the argument at the adapter call.

## Compose

```kotlin
@Composable
fun SecondScreen() {
    val analytics by LocalContext.current.analytics()           // generated accessor
    val greeting = rememberGreetingViewModel(name = "Compose")  // generated, store-retained
    val taps by greeting.taps.collectAsState()
}
```

The nearest `LocalViewModelStoreOwner` — a `NavBackStackEntry`, a Fragment, the
Activity — retains the ViewModel.

The Compose forms exist only when the Compose compiler plugin is applied to that
module; the Kite plugin picks it up with no extra configuration.

## Escape hatch

`Kite.get<T>()` and `by injected()` look a type up at runtime, unchecked by the
compiler. Do not use them in a migration: everything they
did has a generated accessor, which the compiler checks. A lookup before
`Graph.start` and a second `Graph.start` with different arguments are the only
runtime failures left; `KiteConfig(onError = …)` sees them.

## Lifetimes at a call site

| Lifetime | One instance per | Written where |
|---|---|---|
| singleton *(default)* | app process | nothing to write |
| `@Fresh` | injection | on the class |
| `"activity"` | Activity, **survives rotation** | `GraphRules.kt` |
| `"fragment"` | Fragment instance | `GraphRules.kt` |
| a name of your own, `"Session"` | open session | `GraphRules.kt` |

A longer-lived binding may not depend on a shorter-lived one; that is a compile
error, not a runtime leak.

A scope of your own is one line, and its functions are generated:

```kotlin
@Scoped(SessionCache::class, "Session")    // GraphRules.kt

openSession()                              // at login
private val cache by sessionCache()        // like any accessor
closeSession()                             // at logout
```

Hilt custom components (`@DefineComponent` + builder + entry points) translate to
this one line.

## Patterns that replace annotations

- `Lazy<T>` / `() -> T` — defer or re-create; also how a constructor cycle is
  broken, since a deferred edge is not a construction-time edge.
- A parameter with a **default value** is optional: injected if a binding exists,
  defaulted if not, and never a graph argument or a missing-binding error.
- `Set<I>` — implement the interface anywhere, inject the whole set. This is
  `@IntoSet` without the vocabulary, and it collects across module boundaries.
  For `@IntoMap`, put the key on the interface and pick from the set.
- **Marks** — two implementations where the *consumer* chooses: your own
  `SOURCE`-retention annotation on the implementation and on the parameter. Any
  annotation you own works; two implementations sharing one mark is an error.
  Use `@Bind` instead when the choice is the application's.
- Types you don't own (OkHttp, Room) are **supplied**: `class Http(…) :
  Supplies<OkHttpClient>`. Values (a `String` key, a timeout) are **graph
  arguments**, passed to `Graph.start`.

## Tests

Constructor injection means most tests need no framework: call the constructor
with fakes. Test compilations skip inference entirely, so test sources may hold
any number of throwaway implementations without creating ambiguity in the app's
graph.

A test that goes through the real graph — an Activity, a navigation graph —
restarts it per test with `KiteTestRule` (`com.kitedi:test`, already on the test
classpaths) and swaps what it needs with `replace`:

```kotlin
@get:Rule(order = 0)
val kite = KiteTestRule { app ->
    replace<TaskRepository>(FakeTaskRepository())
    Graph.start(app, localDataSource = db.taskDao())
}
```

This replaces `HiltAndroidRule`, `@TestInstallIn`, `@BindValue` and
`@UninstallModules`. Give it `order = 0`.
