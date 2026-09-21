# The board

Every debug build prints a link and starts a board server for you:

```
> Task :app:kiteBoard

  Dependency board: http://localhost:8394  (starting)
```

Open it. You get an infinite canvas of your dependency graph — the one you never
wrote — and it updates as you build.

![The board showing the demo app's graph](/img/board-overview.png)

## What it shows

Click any node to see where it is **provided from** and where it is **injected**,
with the file and line of each. The graph is laid out per module: each module is a
labeled container, a translucent backdrop around its nodes in its own accent
colour. Toggle containers with the **Modules** button or `m`.

- **Graph arguments** appear as external nodes (`apiKey: String`) — the leaves
  your `Graph.start` fills.
- **ViewModels** appear as entry points, since androidx owns their instances.
- **Blocked builds** surface as clickable **decision cards**: an ambiguity between
  two implementations shows the choice, and picking one writes the `@Bind` line
  into the right module's [`GraphRules.kt`](/guide/rules) for you.

![A decision card offering the @Bind that unblocks the build](/img/board-decision-card.png)

Rebuild in Android Studio and watch the board animate the difference — the server
keeps running and picks up each new build.

## Live runtime from a device

By default the board shows the graph as the processor inferred it. Add the
inspector and it also shows what exists on a running device right now — which
singletons have been created, which scopes are open, which ViewModels are live.

The plugin does not add it for you: it is the one Kite dependency you declare by
hand, because it pulls in a server and you should decide whether your debug build
carries one.

```kotlin
dependencies {
    debugImplementation("com.kitedi:inspector:0.1.1")
    releaseImplementation("com.kitedi:inspector-noop:0.1.1")
}
```

Both lines matter. `inspector-noop` is an empty implementation of the same API,
so release builds link against something that does nothing instead of against a
server. Keep the version in step with the plugin's.

Nothing else to configure. The board finds the device over `adb`, forwards a port
adb picks for it, and connects. Every failure is quiet: no adb, no device, or an
app built without the inspector simply leaves you with the static graph. If adb
is not on your `PATH`, set `ANDROID_HOME` or `ADB`.

The on-device inspector binds to `127.0.0.1` exclusively, so it is reachable only
through that forwarded port — never over the network.

## Nothing ships

The board is a host-side developer tool, and the guarantee is structural rather
than a matter of remembering to strip something:

- The graph is written to `build/kite/graph.json` — a build artifact. **No APK
  ever contains it.**
- The board server runs on your machine, as its own process. It is not a library
  your app links against.
- The decision vocabulary (`@Root`/`@Bind`/`@Scoped`) is `SOURCE`-retention and
  `compileOnly`, so it cannot survive compilation.
- The on-device inspector is debug-only and has a no-op counterpart for release
  builds. Your release APK gains no server and no `INTERNET` permission.

Release builds also strip the file-and-line provenance strings from the generated
registries — the data that makes the board useful is exactly the data a shipped
app has no reason to carry.

## Controlling the server

The server auto-starts only on a real build, install or run — never during
`test`, and never under CI. To manage it yourself:

```bash
./gradlew kiteBoard                                      # start it by hand
./gradlew :app:assembleDebug -Pkite.startBoard=false     # print the link, don't start
./gradlew :app:assembleDebug -Pkite.boardPort=9000       # a different port
```

The server's own log is at `build/kite/board-server.log`.
