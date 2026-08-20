package com.kite.di.inspector

import android.content.Context
import android.util.Log
import com.kite.di.graph.GraphJson
import com.kite.di.graph.GraphSnapshot
import com.kite.di.graph.ViewModelUsage
import com.kite.di.runtime.KiteConfig
import com.kite.di.runtime.InspectorRuntimeAccess
import com.kite.di.runtime.observe.GraphEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

private const val TAG = "Kite"

/**
 * Debug-only embedded HTTP/WebSocket server. Binds to 127.0.0.1 exclusively —
 * reachable only via `adb forward`. Serves the web board bundle (AAR assets), the
 * graph snapshot, and the live event stream.
 */
internal object InspectorServer {

    @Volatile
    var url: String? = null
        private set

    private var engine: EmbeddedServer<*, *>? = null
    private var scope: CoroutineScope? = null

    /**
     * ViewModel usages accumulated from [GraphEvent.ViewModelResolved] since process
     * start (events have no replay) — a board that connects late still gets every
     * screen → ViewModel edge with the snapshot.
     */
    private val viewModelUsages = java.util.Collections.synchronizedSet(LinkedHashSet<ViewModelUsage>())

    fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess) {
        if (engine != null) return
        // Defense in depth: even if this artifact is miswired into a release build
        // (implementation instead of debugImplementation), never serve the graph to
        // end users — the inspector is a development tool only.
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.e(
                TAG,
                "Inspector NOT started: the app is not debuggable. Use debugImplementation " +
                    "for :kite:inspector and releaseImplementation for :kite:inspector-noop."
            )
            return
        }
        val app = context.applicationContext
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        serverScope.launch {
            access.events.collect { event ->
                if (event is GraphEvent.ViewModelResolved) viewModelUsages += usageOf(event)
            }
        }
        serverScope.launch {
            delay(200) // stay off the critical app-startup path
            for (port in config.inspectorPort..config.inspectorPort + 5) {
                try {
                    val candidate = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                        module(app, access)
                    }
                    candidate.start(wait = false)
                    engine = candidate
                    url = "http://127.0.0.1:$port"
                    logBanner(port)
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "Inspector: port $port unavailable (${t.message}), trying next")
                }
            }
            Log.e(TAG, "Inspector: no free port in ${config.inspectorPort}..${config.inspectorPort + 5} — server not started")
        }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        engine = null
        url = null
        scope?.cancel()
        scope = null
        viewModelUsages.clear()
    }

    // --- routes -------------------------------------------------------------------

    private fun Application.module(context: Context, access: InspectorRuntimeAccess) {
        install(WebSockets)
        routing {
            get("/api/graph") {
                call.respondText(GraphJson.encode(liveSnapshot(context, access)), ContentType.Application.Json)
            }
            get("/api/meta") {
                call.respondText(metaJson(context).toString(), ContentType.Application.Json)
            }
            webSocket("/api/live") {
                val session = this
                var seq = 0L
                suspend fun send(type: String, build: JsonObjectBuilder.() -> Unit = {}) {
                    val message = buildJsonObject {
                        put("type", type)
                        put("seq", seq++)
                        build()
                    }
                    session.send(Frame.Text(message.toString()))
                }
                suspend fun sendSnapshot() = send("graph.snapshot") {
                    put(
                        "snapshot",
                        GraphJson.json.encodeToJsonElement(
                            GraphSnapshot.serializer(),
                            liveSnapshot(context, access),
                        ),
                    )
                }

                send("hello") {
                    put("schemaVersion", GraphSnapshot.SCHEMA_VERSION)
                    put("appId", context.packageName)
                    put("variant", "debug")
                    put("buildFingerprint", buildFingerprint(context))
                }
                sendSnapshot()

                val forwarder = launch {
                    access.events.collect { event -> forward(event) { type, build -> send(type, build) } }
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        when (parseType(frame.readText())) {
                            "resync" -> sendSnapshot()
                            "ping" -> send("pong")
                        }
                    }
                } finally {
                    forwarder.cancel()
                }
            }
            // Everything else: the web board bundle from AAR assets, SPA-fallback to index.html.
            get("/{path...}") {
                val requested = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                    .ifEmpty { "index.html" }
                val asset = openAsset(context, requested) ?: openAsset(context, "index.html")
                if (asset == null) {
                    call.respondText(
                        "Web board bundle missing from the inspector AAR.\n" +
                            "Rebuild with the :kite:inspector:bundleWebboard task (needs npm), " +
                            "or run the board from webboard/ with `npm run dev`.",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound,
                    )
                } else {
                    call.respondBytes(asset, contentTypeFor(requested))
                }
            }
        }
    }

    private suspend fun forward(
        event: GraphEvent,
        send: suspend (String, JsonObjectBuilder.() -> Unit) -> Unit,
    ) = when (event) {
        is GraphEvent.InstanceCreated -> send("runtime.instanceCreated") {
            put("nodeId", event.key.id)
            put("scopeId", event.scopeId.value)
            put("creationMicros", event.durationMicros)
        }
        is GraphEvent.ScopeOpened -> send("runtime.scopeOpened") {
            put("scopeId", event.scopeId.value)
            put("name", event.name)
            put("parent", event.parent?.value)
        }
        is GraphEvent.ScopeClosed -> send("runtime.scopeClosed") {
            put("scopeId", event.scopeId.value)
        }
        is GraphEvent.ResolutionFailed -> send("runtime.resolutionFailed") {
            put("nodeId", event.key.id)
            putJsonArray("scopePath") { event.scopePath.forEach { add(it.value) } }
            put("message", event.message)
        }
        is GraphEvent.ViewModelResolved -> {
            val usage = usageOf(event)
            send("runtime.viewModelResolved") {
                put("nodeId", usage.nodeId)
                put("ownerId", usage.ownerId)
                put("ownerDisplay", usage.ownerDisplay)
            }
        }
    }

    /** Class references → the dev-only FQN strings of the board protocol. */
    private fun usageOf(event: GraphEvent.ViewModelResolved): ViewModelUsage = ViewModelUsage(
        nodeId = event.viewModel.id,
        ownerId = event.owner.name.replace('$', '.'),
        ownerDisplay = event.owner.simpleName,
    )

    // --- snapshot / meta ------------------------------------------------------------

    @Volatile
    private var compiledGraphText: String? = null

    private fun compiledGraphText(): String? =
        compiledGraphText ?: InspectorServer::class.java.classLoader
            ?.getResourceAsStream("kite/graph.json")
            ?.bufferedReader()?.use { it.readText() }
            ?.also { compiledGraphText = it }

    private fun liveSnapshot(context: Context, access: InspectorRuntimeAccess): GraphSnapshot {
        val compiled = compiledGraphText()?.let { runCatching { GraphJson.decode(it) }.getOrNull() }
            ?: GraphSnapshot(appId = context.packageName, variant = "debug")
        return compiled.copy(
            generatedAt = isoNow(),
            variant = "debug",
            runtime = access.runtimeState().copy(viewModelUsages = viewModelUsages.toList()),
        )
    }

    private fun metaJson(context: Context): JsonObject = buildJsonObject {
        put("appId", context.packageName)
        put("versionName", packageVersionName(context))
        put("buildFingerprint", buildFingerprint(context))
        put("schemaVersion", GraphSnapshot.SCHEMA_VERSION)
    }

    /** Same compile-time graph + same versionCode → same fingerprint → board skips diff animation. */
    private fun buildFingerprint(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update((compiledGraphText() ?: "").toByteArray())
        digest.update(packageVersionName(context).toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun packageVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun parseType(text: String): String? = runCatching {
        GraphJson.json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
    }.getOrNull()

    // --- assets -----------------------------------------------------------------------

    private fun openAsset(context: Context, path: String): ByteArray? = runCatching {
        context.assets.open("webboard/$path").use { it.readBytes() }
    }.getOrNull()

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "js" -> ContentType.Text.JavaScript
        "css" -> ContentType.Text.CSS
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "woff2" -> ContentType("font", "woff2")
        else -> ContentType.Application.OctetStream
    }

    private fun logBanner(port: Int) {
        Log.i(TAG, "┌──────────────────────────────────────────────────────┐")
        Log.i(TAG, "│ Dependency board: run                                │")
        Log.i(TAG, "│   adb forward tcp:$port tcp:$port                      │")
        Log.i(TAG, "│ then open http://localhost:$port                       │")
        Log.i(TAG, "└──────────────────────────────────────────────────────┘")
    }
}
