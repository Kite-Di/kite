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
    val analytics = injected<Analytics>()                       // graph value, host Activity's scope
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

In a `@Preview` there is no Activity, so `injected()` falls back to the app scope.
