# Injecting

Most of the time you inject nothing: a class Kite creates gets its dependencies
as constructor parameters, and that is the whole story. The table below is for
the places Android constructs objects for you.

| Where you are | How you inject |
|---|---|
| A class Kite creates | Just declare constructor parameters |
| Activity / Fragment | `private val presenter: Presenter by injected()` |
| ViewModel | generated adapter: `private val counter by counterViewModel()` |
| `@Composable` | `injected<Analytics>()` and generated `rememberCounterViewModel()` |
| Custom View | `private val analytics: Analytics by injected()` — resolves in the host Activity's scope |
| Service / BroadcastReceiver | `context.injected<Analytics>()` (host Activity's scope if any, app scope otherwise) |
| Anywhere else | `Kite.get<Analytics>()` (app scope) |

## Activities and Fragments

```kotlin
class FirstFragment : Fragment() {
    private val presenter: FirstPresenter by injected()   // resolves in this fragment's scope
}
```

`injected()` resolves through the scope of the component you are in, so an
Activity-scoped dependency asked for from a Fragment resolves in the hosting
Activity's scope. See [lifetimes](/guide/lifetimes).

::: warning A plain class needs a root
`by injected()` is a runtime lookup — the processor cannot see it while reading
constructors. If the type you ask for is a *plain class* (not behind an
interface), add `@Root(FirstPresenter::class)` to the module's
[`GraphRules.kt`](/guide/rules). A class behind an interface is already in the
graph and needs nothing.
:::

## ViewModels

A ViewModel is an entry point, not an ordinary binding — androidx owns the
instance. See [ViewModels & Compose](/guide/viewmodels).

## Custom Views

A `View` gets its dependencies from the Activity hosting it, which is usually
what you want for anything Activity-scoped:

```kotlin
class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val analytics: Analytics by injected()
}
```

## Escape hatch

`Kite.get<T>()` resolves in the app scope from anywhere. It is the right tool in
a `ContentProvider`, a `WorkManager` factory, or legacy code you are not ready to
convert — and the wrong tool everywhere a constructor parameter would do, because
it hides the dependency from the graph and from the board.
