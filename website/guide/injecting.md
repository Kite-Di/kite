# Injecting

Most of the time you inject nothing: a class Kite creates gets its dependencies
as constructor parameters, and that is the whole story. The table below is for
the places Android constructs objects for you.

| Where you are | How you inject |
|---|---|
| A class Kite creates | Just declare constructor parameters |
| Activity / Fragment | generated accessor: `private val presenter: Presenter by presenter()` |
| ViewModel | generated adapter: `private val counter by counterViewModel()` |
| `@Composable` | `val analytics by LocalContext.current.analytics()` and generated `rememberCounterViewModel()` |
| Custom View | `private val analytics: Analytics by analytics()` — resolves in the host Activity's scope |
| Service / BroadcastReceiver / Application | `val analytics by context.analytics()` — app-wide bindings only |

## Generated accessors

For every type you may ask the graph for, the processor generates a function
named after it — `Analytics` gets `analytics()`, `FirstPresenter` gets
`firstPresenter()` — the same way a ViewModel gets `counterViewModel()`:

```kotlin
class FirstFragment : Fragment() {
    private val presenter: FirstPresenter by firstPresenter()   // resolves in this fragment's scope
}
```

Because the lookup is generated code, the compiler checks it. Nothing is left to
fail on the device:

- **No binding, no function.** Ask for a type nothing provides and the call is an
  unresolved reference — in the IDE, before the build runs.
- **Wrong scope, wrong receiver.** An accessor exists only where its binding's
  lifetime is available:

  | The binding lives… | You can ask from |
  |---|---|
  | app-wide (singleton, or nothing scoped) | any `Context` — Activity, Service, Application — a `Fragment`, a `View` |
  | per Activity | an `Activity`, a `Fragment` |
  | per Fragment | a `Fragment` |

  An Activity-scoped session asked for from a Service is a compile error, not a
  crash. Something with no scope of its own — a `@Fresh` class — counts as the
  shortest lifetime it reaches through its own dependencies.

Accessors are generated for the types a lookup legitimately names: every
interface a class is bound to, every type a [`Supplies<T>`](/guide/patterns#types-you-don-t-own-supplies-t)
supplies (declared beside its supplier), and every class with a
[`@Root`](/guide/rules). A [marked](/guide/patterns#choosing-between-implementations-marks)
binding gets its mark in the name: `stripeGateway()`.

::: tip A plain class needs a root
A plain class — not behind an interface — that only constructors reach gets no
accessor: nothing should look it up. If you do want it at runtime, add
`@Root(FirstPresenter::class)` to the module's [`GraphRules.kt`](/guide/rules)
and its accessor appears. A class behind an interface needs nothing.
:::

An accessor returns a `Lazy`, so it works with `by`. For a one-off read, take
`.value`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, apiKey = BuildConfig.API_KEY)
        appInitializer().value.runAll()
    }
}
```

## ViewModels

A ViewModel is an entry point, not an ordinary binding — androidx owns the
instance. See [ViewModels & Compose](/guide/viewmodels).

## Custom Views

A `View` gets its dependencies from the Activity hosting it:

```kotlin
class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val analytics: Analytics by analytics()
}
```

Only app-wide bindings are offered on a `View`: a view inflated with the
application context has no Activity scope to resolve in.

## When something can still fail

Two things the build cannot see, because they are about the order your code runs in:

- a lookup before `Graph.start` — start the graph first thing in
  `Application.onCreate`;
- a second `Graph.start` with different arguments (the same arguments again change
  nothing).

`KiteConfig(onError = …)` sees either failure just before it is thrown — the place
to hand it to your crash reporter:

```kotlin
Graph.start(this, apiKey = BuildConfig.API_KEY, config = KiteConfig(onError = FirebaseCrashlytics.getInstance()::recordException))
```

Exceptions your own constructors throw are yours; Kite does not catch them.

## Lookups by type

`Kite.get<T>()`, `by injected()` and `injected<T>()` look a type up when the code
runs, so the compiler cannot check them. They require an explicit opt-in, which is
the call site saying it accepts a runtime failure:

```kotlin
@OptIn(UncheckedLookup::class)
val legacy = Kite.get<LegacyTracker>()
```

They are for the cases an accessor cannot reach — a custom scope (until scopes are
typed), a class with no `Context` in sight, legacy code you are not ready to
convert — and they hide the dependency from the graph and the board, so prefer a
constructor parameter wherever one would do.
