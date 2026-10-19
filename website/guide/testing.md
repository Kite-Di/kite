# Testing

Constructor injection means most tests need no framework at all — you are calling
a constructor:

```kotlin
val presenter = DefaultFirstPresenter(
    DefaultGreetingUseCase(FakeRepo()),
    SessionState(),
)
```

There is nothing to install, no test component to build, and no rule to apply.
A fake is a class that implements the interface. Reach for the rest of this page
only when a test has to go through the real graph — an Activity, a navigation
graph, a screen that builds its own ViewModel.

## Test compilations never run the processor

Every test compilation — unit tests, instrumentation tests, test fixtures,
screenshot tests — skips inference: running it over test sources would generate a
second registry that shadows the main graph. Your test sources can therefore hold
as many throwaway implementations of an interface as they like without producing
an [ambiguity error](/guide/errors#two-implementations-of-one-interface) in the app's graph.

## Testing that the graph itself is correct

The graph is validated at build time, so "does it wire up" is answered by
compiling, not by a test. What is worth a test is the behaviour of the classes in
it — which is ordinary unit testing.

## A graph lives as long as its Application

`Graph.start` runs once, from `Application.onCreate`, and the graph it builds —
every singleton in it — lives until that `Application` is gone:

- **On a device**, one `Application` serves the whole instrumentation run. Calling
  `Graph.start` a second time for it is a programming error and throws.
- **Under Robolectric**, every test gets a fresh `Application`, and its
  `onCreate` starts a fresh graph. Nothing carries over between tests.

So on a device, a test that changes state through the graph — creates a record,
fills a cache — hands that state to the next test, unless something restarts the
graph in between. That something is `KiteTestRule`.

## KiteTestRule

`com.kitedi:test` is added to your unit and instrumentation test classpaths by the
plugin. Its one entry point is a JUnit rule that restarts the graph around every
test:

```kotlin
@RunWith(AndroidJUnit4::class)
class TasksTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        ToDoDatabase::class.java,
    ).build()

    @get:Rule(order = 0)
    val kite = KiteTestRule { app ->
        Graph.start(
            app,
            localDataSource = db.taskDao(),
            dispatcher = Dispatchers.Main,
            scope = TestScope(),
        )
    }

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<TodoTestActivity>()

    @Test
    fun createdTaskIsListed() = runTest {
        ApplicationProvider.getApplicationContext<Context>().taskRepository().value
            .createTask("Title", "Description")
        composeTestRule.setContent { TodoNavGraph() }
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
    }
}
```

Before each test the rule discards whatever graph is running — the one your
production `Application` started, or the previous test's — and runs the block,
which starts a new one. After the test it discards that one too. The block is
the same `Graph.start` call you make in production, with the arguments the test
wants, so the compiler still checks every one of them.

Give the rule `order = 0`. It has to start the graph before an Activity rule
launches anything and discard it only after that Activity has closed.

Under Robolectric the rule is optional — every test already gets a new graph —
but it is how you [replace a binding](#replacing-a-binding) there too.

## Replacing a binding

A graph argument is already yours to choose: pass a different value to
`Graph.start`. Anything the graph builds itself — an implementation it inferred,
a class it constructs — is replaced with `replace`, before the graph starts:

```kotlin
private val repository = FakeTaskRepository()

@get:Rule(order = 0)
val kite = KiteTestRule { app ->
    replace<TaskRepository>(repository)
    Graph.start(app, localDataSource = db.taskDao(), dispatcher = Dispatchers.Main, scope = TestScope())
}
```

Every class that asks for a `TaskRepository` now gets `repository`, and the test
holds the same instance to set up data and check results. `DefaultTaskRepository`
is never constructed — unless something asks for `DefaultTaskRepository` by its
own class, which keeps resolving to the real one: a replacement takes the type you
name, not the types next to it.

The type argument is the key. Write it out: `replace(repository)` with
`val repository = FakeTaskRepository()` would replace `FakeTaskRepository`, which
nothing in the graph provides — and fails the start saying so.

What a replacement is:

- **One instance, shared.** Whatever the lifetime of the binding it replaces — a
  singleton, `@Scoped(…, "activity")`, `@Fresh` — the replacement is the instance
  you passed, everywhere, for the whole test.
- **Checked when the graph starts.** Replacing a type nothing in the graph
  provides fails `Graph.start` instead of being silently ignored — a stale
  replacement after a refactoring is an error, not a no-op.
- **Test-only by construction.** `replace` lives in `com.kitedi:test`, which is
  never on an application's runtime classpath.

A [marked](/guide/patterns#choosing-between-implementations-marks) implementation
is replaced under its mark — `replace<Gateway>(fakeStripe, mark = Stripe::class)` —
and leaves the other marks and the unmarked binding alone.

Graph arguments are not replaced: they are already parameters of `Graph.start`,
so pass the test's value there.

## Picking an implementation per build type

When a fake should be the implementation in a whole build — a `demo` flavor that
runs without a backend, say — that is a decision, not a test: put a `@Bind` line in
that source set's `GraphRules.kt` (`src/demo/kotlin/…`). The processor reads the
variant's sources, so the decision is compile-checked and the other variants keep
their own. Prefer `replace` for tests: a `@Bind` in `src/debug` would ship the fake
in the debug build you run by hand.

## Errors

```
Kite is already started for this Application.
  hint: Graph.start runs once per process, from Application.onCreate. In an
  instrumentation test, KiteTestRule restarts the graph around every test.
```

```
replace<com.example.TaskRepository>: nothing in the graph provides it.
  hint: a graph argument is passed to Graph.start, not replaced; otherwise the
  type may have been renamed or removed — delete the replacement.
```

```
replace<android.content.Context>: Kite provides it — it is the Application passed to Graph.start.
  hint: pass the test's value to Graph.start instead.
```

```
replace<com.example.TaskRepository> after Graph.start.
  hint: call replace before Graph.start — the graph is built when it starts.
```

```
KiteTestRule: the block did not start the graph.
  hint: call Graph.start(app, …) inside KiteTestRule { app -> … }.
```

## Don't keep graph objects between tests

Instances from a discarded graph keep working — the rule does not invalidate
them — but they are not part of the next graph. Don't keep them in companion
objects or other static fields: the next test would be talking to the previous
test's objects.
