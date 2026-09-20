package com.kite.di.board

import java.io.File

/**
 * Entry point of the board process. The Gradle plugin spawns this detached after a
 * debug build and goes away; the board outlives the build so the page stays open
 * and updates on the next one.
 *
 * Arguments are plain `--key=value` pairs — no parser, no dependency:
 *   --port=8394
 *   --graph=<app module's build/kite/graph.json>
 *   --fragments=<path-separator-joined library module graph.json files>
 *   --decisions=<build/kite/decisions.json>
 *   --repo=<root dir; decision writes resolve against it>
 */
object BoardMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.mapNotNull { arg ->
            val trimmed = arg.removePrefix("--")
            val idx = trimmed.indexOf('=')
            if (idx <= 0) null else trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        }.toMap()

        val port = options["port"]?.toIntOrNull() ?: 8394
        val graphFile = File(options["graph"] ?: "app/build/kite/graph.json")
        val fragments = options["fragments"].orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
        val decisionsFile = File(options["decisions"] ?: graphFile.resolveSibling("decisions.json").path)
        val repoRoot = File(options["repo"] ?: ".").absoluteFile

        val store = GraphStore(graphFile, fragments, decisionsFile)
        val server = BoardServer(port, store, repoRoot)

        val bridge = AdbBridge(
            adb = AdbBridge.locateAdb(),
            expectedAppId = { store.snapshot().appId },
            onState = { server.onRuntimeState(it) },
            onEvent = { server.onRuntimeEvent(it) },
            log = ::log,
        )
        server.onClientConnected = { bridge.requestResync() }

        log("dependency board → http://localhost:$port")
        log("watching ${graphFile.path}")
        for (fragment in fragments) log("+ module fragment ${fragment.path}")
        if (store.graphLoaded()) {
            log("graph loaded: ${store.snapshot().nodes.size} nodes, fingerprint ${store.fingerprint}")
        } else {
            log("graph not found yet — build the app once (any debug build)")
        }

        bridge.start()
        server.start()
    }

    private fun log(message: String) = println("[board] $message")
}
