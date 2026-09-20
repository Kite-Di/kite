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
A fake is a class that implements the interface.

## Unit tests never run the processor

Test compilations skip inference entirely: running it over test sources would
generate a second registry that shadows the main graph. Your test sources can
therefore hold as many throwaway implementations of an interface as they like
without producing an [ambiguity error](/guide/errors#duplicate-binding) in the
app's graph.

## Testing that the graph itself is correct

The graph is validated at build time, so "does it wire up" is answered by
compiling, not by a test. What is worth a test is the behaviour of the classes in
it — which is ordinary unit testing.

## Instrumentation tests

For a test that runs against a real `Application`, `Graph.start` takes the same
[graph arguments](/guide/inference#graph-arguments) it takes in production, so a
test `Application` can pass fakes in:

```kotlin
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, apiKey = "test-key", timeoutMillis = 100)
    }
}
```

Anything you want to replace wholesale is a graph argument or an interface with a
`@Bind` — both of which are decided in ordinary code you control per build type.
