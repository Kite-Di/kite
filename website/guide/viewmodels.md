# ViewModels & Compose

## ViewModels are entry points

The processor sees the `ViewModel` supertype and generates a `<name>ViewModel()`
adapter for Activities and Fragments. androidx's `ViewModelStore` owns the
instance — rotation retention, `onCleared` — and the graph fills the constructor.
[Graph scopes](/guide/lifetimes) never apply, because the store is already the
cache.

```kotlin
class CounterViewModel(private val analytics: Analytics) : ViewModel() {
    var clicks = 0; private set
    fun onFabClick() { clicks++; analytics.track("fab_clicks:$clicks") }
}

class MainActivity : AppCompatActivity() {
    private val counter: CounterViewModel by counterViewModel()   // generated, survives rotation
}
```

The graph fills the constructor from the host's scope, so an adapter exists only on
hosts that have what it needs: a ViewModel whose dependencies live per Fragment
gets a `Fragment` adapter and no Activity one — asking for it from an Activity does
not compile. A [scope of your own](/guide/lifetimes#custom-scopes) is open app-wide,
so it asks nothing of the host.

## Runtime arguments need no "assisted injection"

A ViewModel parameter the graph cannot provide simply becomes a parameter of the
generated adapter. `SavedStateHandle` is supplied automatically:

```kotlin
class CheckoutViewModel(
    flow: CheckoutFlow,
    orderId: String,
    saved: SavedStateHandle,
) : ViewModel()
```

```kotlin
// in the fragment:
private val vm by checkoutViewModel(orderId = args.orderId)
```

There is no `@AssistedInject`, no factory interface and no `@AssistedFactory` —
the concept does not exist because the adapter is generated from the constructor
that already describes the split.

## Compose

Apply the Compose compiler plugin as usual; the Kite plugin notices and does the
rest — it adds the Compose helpers and gives every ViewModel adapter a
`@Composable remember…` form. Same graph, no extra concepts:

```kotlin
@Composable
fun SecondScreen() {
    val analytics by LocalContext.current.analytics()           // generated accessor, app-wide binding
    val greeting = rememberGreetingViewModel(name = "Compose")  // generated; store-retained
    val taps by greeting.taps.collectAsState()
    Button(onClick = { greeting.onTap(); analytics.track("tap") }) {
        Text("Taps: $taps")
    }
}
```

The nearest `LocalViewModelStoreOwner` — a `NavBackStackEntry`, a Fragment, the
Activity — retains the ViewModel. `SavedStateHandle` and runtime arguments work
exactly as they do in the Activity and Fragment adapters.

Graph values come from the composition's `Context` through the same
[generated accessors](/guide/injecting#generated-accessors) as everywhere else; the
`@Composable remember…` adapter exists when the ViewModel's dependencies are
available from the host Activity.
