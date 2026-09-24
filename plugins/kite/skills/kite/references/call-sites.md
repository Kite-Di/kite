# Call sites

<https://kitedi.com/guide/injecting> · <https://kitedi.com/guide/viewmodels>

Most of the time there is no call site: a class Kite creates takes its
dependencies as constructor parameters, and that is the whole story. This file is
for the places **Android** constructs the object.

| Where you are | How you inject |
|---|---|
| A class Kite creates | declare constructor parameters — nothing else |
| Activity / Fragment | `private val presenter: Presenter by injected()` |
| ViewModel | generated adapter: `private val counter by counterViewModel()` |
| `@Composable` | `injected<Analytics>()`, `rememberCounterViewModel()` |
| Custom `View` | `private val analytics: Analytics by injected()` — resolves in the host Activity's scope |
| Service / BroadcastReceiver | `context.injected<Analytics>()` |
| Anywhere else | `Kite.get<Analytics>()` — app scope |

## Activities and Fragments

```kotlin
class FirstFragment : Fragment() {
    private val presenter: FirstPresenter by injected()
}
```

`injected()` resolves through the scope of the component it is called from, so an
Activity-scoped dependency asked for from a Fragment resolves in the hosting
Activity's scope.

**The `@Root` check.** `by injected()` is a runtime lookup the processor cannot
see while reading constructors. Ask which case the requested type is:

- behind a project interface → already in the graph, **nothing to add**;
- a plain class, resolved only this way → add `@Root(FirstPresenter::class)` to
  the module's `GraphRules.kt`.

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
    val analytics = injected<Analytics>()                       // host Activity's scope
    val greeting = rememberGreetingViewModel(name = "Compose")  // generated, store-retained
    val taps by greeting.taps.collectAsState()
}
```

The nearest `LocalViewModelStoreOwner` — a `NavBackStackEntry`, a Fragment, the
Activity — retains the ViewModel. In a `@Preview` there is no Activity, so
`injected()` falls back to the app scope.

The Compose forms exist only when the Compose compiler plugin is applied to that
module; the Kite plugin picks it up with no extra configuration.

## Escape hatch

`Kite.get<T>()` resolves in the app scope from anywhere — right in a
`ContentProvider`, a `WorkManager` factory, or legacy code not yet converted.
Wrong everywhere a constructor parameter would do: it hides the edge from the
graph and from the board, and a plain class reached this way needs a `@Root`.

## Lifetimes at a call site

| Lifetime | One instance per | Written where |
|---|---|---|
| singleton *(default)* | app process | nothing to write |
| `@Fresh` | injection | on the class |
| `"activity"` | Activity, **survives rotation** | `GraphRules.kt` |
| `"fragment"` | Fragment instance | `GraphRules.kt` |
| custom `"<Name>", level = N` | your scope, closed by you | `GraphRules.kt` |

A longer-lived binding may not depend on a shorter-lived one; that is a compile
error, not a runtime leak.

A custom scope is opened and closed by hand:

```kotlin
val session = Kite.openScope(ScopeId("session"), name = "Session", level = 10)
val cache: SessionCache = session.get()
session.close()
```

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
- Types you don't own (OkHttp, Room, a `String`) are **graph arguments**, built
  with ordinary code at the `Graph.start` call site.

## Tests

Constructor injection means most tests need no framework: call the constructor
with fakes. Test compilations skip inference entirely, so test sources may hold
any number of throwaway implementations without creating ambiguity in the app's
graph. For instrumentation tests, a test `Application` passes fakes through the
same `Graph.start` arguments.
