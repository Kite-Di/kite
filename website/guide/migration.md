# Migrating from Hilt or Dagger

Most of this migration is deletion. Your classes already describe the graph —
Hilt made you describe it a second time, and that second description is what goes
away.

## The translation table

| Hilt / Dagger | Kite |
|---|---|
| `@Inject constructor(…)` | nothing — a constructor is already a constructor |
| `@Module` + `@Binds` | implementing the interface |
| `@Module` + `@Provides` for a type you own | nothing — the class is the binding |
| `@Module` + `@Provides` for a library type | a [graph argument](/guide/inference#graph-arguments), constructed at the `Graph.start` call site |
| `@Singleton` | the default — delete it |
| `@Qualifier` / `@Named("x")` | a [mark](/guide/patterns#choosing-between-implementations-marks) — your own annotation on both sides; for graph arguments, the parameter name |
| `@InstallIn(SingletonComponent::class)` | nothing — there are no components |
| `@ActivityScoped` | `@Scoped(X::class, "activity")` in `GraphRules.kt` |
| `@HiltAndroidApp` | `Graph.start(this)` in `onCreate` |
| `@AndroidEntryPoint` | nothing — `by injected()` works without it |
| `@HiltViewModel` | nothing — the adapter is generated from the `ViewModel` supertype |
| `@AssistedInject` + `@AssistedFactory` | an unprovidable constructor parameter becomes an adapter parameter |
| `@IntoSet` | inject `Set<I>` |
| `@IntoMap` + `@MapKey` | a key property on the interface, then `Set<I>` |
| `EntryPointAccessors` | `Kite.get<T>()` |
| `dagger.Lazy<T>` / `Provider<T>` | `kotlin.Lazy<T>` / `() -> T` |

One entry in that table has no replacement at all: components. Scopes follow the
Android lifecycle on their own, so there is nothing to build or install.

## Doing it

**1. Apply the plugin, remove Hilt's.** One `id("com.kitedi")` per module with
injectable classes. See [getting started](/guide/getting-started).

**2. Delete the annotations.** `@Inject` on constructors, `@Singleton`,
`@AndroidEntryPoint`, `@HiltViewModel`, `@HiltAndroidApp`. A find-and-replace
across the project is the right tool; nothing replaces them.

**3. Delete the `@Binds` modules.** A module whose only job was
`@Binds abstract fun bind(impl: RealFoo): Foo` describes what `RealFoo : Foo`
already says. Delete the module. If an interface has two implementations, the
build will stop and hand you the `@Bind` line to put in
[`GraphRules.kt`](/guide/rules) — that one was a real decision, and it is the
only part of the module worth keeping.

**4. Turn `@Provides` for library types into graph arguments.** A provider like

```kotlin
@Provides @Singleton
fun okHttp(@Named("timeout") timeout: Long): OkHttpClient =
    OkHttpClient.Builder().callTimeout(timeout, MILLISECONDS).build()
```

becomes ordinary code where ordinary code goes:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder().callTimeout(5, SECONDS).build()
        Graph.start(this, okHttp = http)
    }
}
```

The parameter appears because something in your graph declares `okHttp: OkHttpClient`
and nothing provides it. You do not write the wiring; you write the object.

**5. Move the scopes.** Each `@ActivityScoped`/`@FragmentScoped` becomes a
`@Scoped` line in the owning module's `GraphRules.kt`. Everything that was
`@Singleton` needs no line at all.

**6. Build, and let the errors finish the job.** What surfaces will mostly be:

- **Ambiguity** where a `@Binds` used to choose for you → a `@Bind` line
- **Unused binding** for a plain class you resolved through `EntryPointAccessors`
  → a `@Root` line, because a runtime lookup is invisible to the processor
- **Scope violation** that Dagger would have caught too, now with both file
  positions

Every message is in [build errors](/guide/errors).

## What gets harder

Be honest with yourself about these before committing to the move:

- **Graph arguments unify by name.** Two unrelated `String` leaves called `url`
  become one argument. Hilt's qualifiers were explicit about this; names are not.
- **There is no `@BindValue` for tests.** Constructor injection means tests
  construct what they need directly, which is simpler — but a test suite built
  around swapping bindings in a component needs rewriting, not translating.

## What you can stop thinking about

Component hierarchies. Whether a module is installed in the right component.
Whether `@Singleton` on a class matches `@Singleton` on its component. Keeping a
`@Binds` module in sync with a renamed implementation. Writing an
`@AssistedFactory` interface for a ViewModel that needs an id.

None of those have an equivalent here, because none of them were about your
application — they were about describing your application to a framework.
