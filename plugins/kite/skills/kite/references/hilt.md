# Migrating off Hilt or Dagger

<https://kitedi.com/guide/migration>

Most of this migration is deletion. The classes already describe the graph; Hilt
made the project describe it a second time, and that second description is what
goes away.

## The translation table

| Hilt / Dagger | Kite |
|---|---|
| `@Inject constructor(…)` | nothing — a constructor is already a constructor |
| `@Module` + `@Binds` | implementing the interface |
| `@Module` + `@Provides` for a type you own | nothing — the class is the binding |
| `@Module` + `@Provides` for a library type | a graph argument, constructed at the `Graph.start` call site |
| `@Singleton` | the default — delete it |
| `@Qualifier` / `@Named("x")` | a mark (your own annotation on both sides); for graph arguments, the parameter name |
| `@InstallIn(SingletonComponent::class)` | nothing — there are no components |
| `@ActivityScoped` | `@Scoped(X::class, "activity")` in `GraphRules.kt` |
| `@FragmentScoped` | `@Scoped(X::class, "fragment")` in `GraphRules.kt` |
| `@HiltAndroidApp` | `Graph.start(this)` in `onCreate` |
| `@AndroidEntryPoint` | nothing — `by injected()` works without it |
| `@HiltViewModel` | nothing — the adapter is generated from the `ViewModel` supertype |
| `@AssistedInject` + `@AssistedFactory` | an unprovidable constructor parameter becomes an adapter parameter |
| `@IntoSet` | inject `Set<I>` |
| `@IntoMap` + `@MapKey` | a key property on the interface, then `Set<I>` |
| `EntryPointAccessors` | `Kite.get<T>()` |
| `dagger.Lazy<T>` / `Provider<T>` | `kotlin.Lazy<T>` / `() -> T` |

Components have no row: scopes follow the Android lifecycle on their own, so
there is nothing to build or install.

## Order of work

**1. Apply the Kite plugin, remove Hilt's.** One `alias(libs.plugins.kite)` per
module with injectable classes. Remove `dagger.hilt.android.plugin`, the
`kapt`/`ksp` Hilt entries, `hilt-android`, `hilt-compiler`,
`hilt-navigation-compose`, and `kapt` itself if Hilt was its only user.

**2. Delete the annotations.** `@Inject` on constructors, `@Singleton`,
`@AndroidEntryPoint`, `@HiltAndroidApp`, `@HiltViewModel`, `@InstallIn`. A
project-wide find-and-replace is the right tool — nothing replaces them.

Field injection (`@Inject lateinit var x: T`) is the one that does not simply
disappear: it becomes `private val x: T by injected()`, or better, a constructor
parameter if the class is one Kite creates.

**3. Delete the `@Binds` modules.** A module whose only job was
`@Binds abstract fun bind(impl: RealFoo): Foo` describes what `RealFoo : Foo`
already says. If an interface has two implementations the build will stop and
hand over the exact `@Bind` line — that one was a real decision, and it is the
only part of the module worth keeping.

**4. Turn `@Provides` for library types into graph arguments.**

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

The parameter appears because something in the graph declares
`okHttp: OkHttpClient` and nothing provides it. You do not write the wiring; you
write the object.

A `@Provides` for a type the project **owns** is deleted outright — the class is
already the binding.

**5. Move the scopes.** Each `@ActivityScoped` / `@FragmentScoped` becomes a
`@Scoped` line in the **owning** module's `GraphRules.kt`. Everything that was
`@Singleton` needs no line at all.

**6. Build, and let the errors finish the job.** What surfaces is mostly:

- **Duplicate binding** where a `@Binds` used to choose → a `@Bind` line;
- **Unused binding** for a plain class resolved through `EntryPointAccessors` →
  a `@Root` line, because a runtime lookup is invisible to the processor;
- **Scope violation** that Dagger would have caught too, now with both file
  positions.

## Never leave both applied

Hilt and Kite on the same module means two processors generating entry points for
one Activity. Finish the plugin swap per module before building.

## Flag these to the user

Two things genuinely get harder, and they should hear it rather than discover it:

- **Graph arguments unify by name.** Two unrelated `String` leaves called `url`
  become one argument filled once. Hilt's qualifiers were explicit about this;
  names are not. Rename leaves to something globally unique while migrating.
- **There is no `@BindValue`.** A test suite built around swapping bindings in a
  test component needs rewriting, not translating. Constructor injection makes
  the replacement simpler — tests construct what they need — but it is a rewrite.
