# Contributing

Thanks for looking. Kite is young, so the most valuable contributions right now
are reports of real projects it does not fit, and the error messages that failed
to tell you what to do.

## Before you open a pull request

Open an issue first for anything that changes behaviour. The design has a strong
opinion — the graph is inferred, only decisions are written down — and a change
that adds a new annotation or a new concept needs to argue against that opinion
before it needs code.

Typos, documentation and test coverage need no discussion. Send them.

## Building

```bash
./gradlew build                  # everything
./gradlew :app:assembleDebug     # the demo app, and the board link it prints
```

The Gradle plugin is an included build under `kite/gradle-plugin`, so changes to
it take effect without publishing anything.

## Repository layout

```
kite/graph-core       graph model + JSON + diff (shared compile-time/runtime/board)
kite/processor        KSP: inference + validation + codegen + graph.json export
kite/runtime          Android runtime container + scope tree
kite/compose          injected<T>() composable + injectedViewModel plumbing
kite/gradle-plugin    id("com.kitedi") — one-plugin setup (included build)
kite/inspector        debug-only on-device server reporting the live runtime
kite/inspector-noop   release stand-in (empty API)
kite/board            host-side board server; carries the built web UI in its jar
webboard/             TypeScript + Vite infinite-canvas frontend
website/              VitePress sources for kitedi.com
scripts/              check-apk-safety.sh, setup-signing.sh
```

The demo app under `app/`, `core/` and `feature/` is cut like a production
codebase, and is the best place to see the conventions: `core/network` is
interface-and-implementation throughout and needs no rules file at all;
`core/analytics` has two implementations of one interface, so its
`GraphRules.kt` holds the single `@Bind` that picks between them;
`core/designsystem` and the `:api` modules carry no plugin, because nothing in
them is injectable.

## Tests

```bash
./gradlew :app:testDebugUnitTest :kite:processor:test :kite:graph-core:test :kite:board:test
cd webboard && npm ci && npm test        # the canvas
```

`:kite:graph-core` holds a golden snapshot of the exported graph JSON. When a
change to the model is intended, regenerate it and review the diff:

```bash
./gradlew :kite:graph-core:test -PgoldenUpdate
```

Validation messages are a documented interface — [the errors
page](https://kitedi.com/guide/errors) quotes them verbatim. Changing one means
updating `website/guide/errors.md` in the same pull request.

## The rule that is not negotiable

No APK may ever contain the graph, the board, the inspector, or the `INTERNET`
permission. Before sending anything that touches the processor, the runtime or
the Gradle plugin:

```bash
./gradlew :app:assembleDebug :app:assembleRelease && ./scripts/check-apk-safety.sh
```

## Documentation

User-facing documentation lives in `website/` and is published to
[kitedi.com](https://kitedi.com).

```bash
cd website && npm install && npm run dev
```

The design documents in `docs/sdd/` describe why the system is shaped the way it
is. They are the place to look before proposing an architectural change, and the
place to update after making one.

## Style

Match the file you are editing. Comments in this codebase explain why something
is the way it is, not what the line does — if a comment would only restate the
code, leave it out.

## License

By contributing you agree that your work is licensed under the Apache License
2.0, the same terms as the project.
