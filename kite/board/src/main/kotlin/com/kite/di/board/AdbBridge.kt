package com.kite.di.board

import com.kite.di.graph.GraphJson
import com.kite.di.graph.RuntimeState
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runtime facts from a device, fetched without asking the developer for anything.
 *
 * The graph is on this machine; what only the phone knows is what actually ran.
 * The bridge finds the device over adb, forwards a port **adb** picks (so it can
 * never collide with the board's own), checks the app is the one the graph
 * describes, and streams the inspector's events.
 *
 * Every failure is quiet: no adb, no device, an app built without the inspector,
 * a cable pulled mid-session — the board keeps serving the static graph.
 */
class AdbBridge(
    private val adb: File?,
    private val expectedAppId: () -> String,
    private val onState: (RuntimeState?) -> Unit,
    private val onEvent: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val poller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kite-board-adb").apply { isDaemon = true }
    }
    private val reader = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "kite-board-adb-stream").apply { isDaemon = true }
    }

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var serial: String? = null
    @Volatile private var hostPort: Int = 0

    fun start() {
        if (adb == null) {
            log("adb not found — runtime badges need it (set ANDROID_HOME or ADB)")
            return
        }
        poller.scheduleWithFixedDelay({ runCatching { tick() } }, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    /** A board just opened: ask the device for a current ledger, not the one from connect time. */
    fun requestResync() {
        val port = hostPort
        if (port == 0) return
        reader.execute {
            val payload = runCatching { get("http://127.0.0.1:$port/api/runtime") }.getOrNull()
            if (payload != null) publishState(payload)
        }
    }

    private fun tick() {
        if (connection != null) return
        for (device in devices()) {
            if (attach(device)) return
        }
    }

    private fun devices(): List<String> = runCatching {
        exec(listOf(adb!!.absolutePath, "devices"))
            .lineSequence()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" }
            .map { it[0] }
            .toList()
    }.getOrDefault(emptyList())

    /**
     * The inspector takes the first free port in its range, so the bridge walks it.
     * `adb forward tcp:0` makes adb allocate the host side and print it.
     */
    private fun attach(device: String): Boolean {
        for (devicePort in DEVICE_PORT_FIRST..DEVICE_PORT_LAST) {
            val port = runCatching {
                exec(listOf(adb!!.absolutePath, "-s", device, "forward", "tcp:0", "tcp:$devicePort")).trim().toInt()
            }.getOrNull() ?: continue

            val meta = runCatching { get("http://127.0.0.1:$port/api/meta") }.getOrNull()
            val appId = meta?.let { Regex("\"appId\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
            if (appId != null && appId == expectedAppId()) {
                serial = device
                hostPort = port
                log("runtime connected: $appId on $device (adb tcp:$port)")
                reader.execute { stream(port) }
                return true
            }
            removeForward(device, port)
        }
        return false
    }

    /**
     * Server-sent events: one `data:` line per message, which a plain URL
     * connection can read. The device is the only consumer of this stream, so it
     * needs nothing a WebSocket would add.
     */
    private fun stream(port: Int) {
        val url = URL("http://127.0.0.1:$port/api/events")
        val open = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
        }
        connection = open
        try {
            open.inputStream.bufferedReader().use { source -> pump(source) }
        } catch (e: Exception) {
            // a stopped app, a pulled cable — both land here
        } finally {
            detach()
        }
    }

    private fun pump(source: BufferedReader) {
        while (true) {
            val line = source.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload.contains("\"runtime.state\"")) publishState(payload) else onEvent(payload)
        }
    }

    private fun publishState(payload: String) {
        val runtime = runCatching {
            val element = GraphJson.json.parseToJsonElement(payload)
            val state = element as? kotlinx.serialization.json.JsonObject
            val runtimeElement = state?.get("runtime") ?: element
            GraphJson.json.decodeFromJsonElement(RuntimeState.serializer(), runtimeElement)
        }.getOrNull()
        onState(runtime)
    }

    private fun detach() {
        connection = null
        onState(null)
        val device = serial
        val port = hostPort
        serial = null
        hostPort = 0
        if (device != null && port != 0) {
            log("runtime disconnected ($device) — static graph only")
            removeForward(device, port)
        }
    }

    private fun removeForward(device: String, port: Int) {
        runCatching { exec(listOf(adb!!.absolutePath, "-s", device, "forward", "--remove", "tcp:$port")) }
    }

    private fun exec(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        return output
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DEVICE_PORT_FIRST = 8394
        private const val DEVICE_PORT_LAST = DEVICE_PORT_FIRST + 5
        private const val POLL_SECONDS = 3L
        private const val PROBE_TIMEOUT_MS = 1500

        /** adb the way Android Studio finds it: explicit env first, then the default SDK path. */
        fun locateAdb(): File? {
            System.getenv("ADB")?.let { if (File(it).isFile) return File(it) }
            for (home in listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))) {
                val candidate = File(home, "platform-tools/adb")
                if (candidate.isFile) return candidate
            }
            val userHome = System.getProperty("user.home")
            for (path in listOf("Library/Android/sdk/platform-tools/adb", "Android/Sdk/platform-tools/adb")) {
                val candidate = File(userHome, path)
                if (candidate.isFile) return candidate
            }
            return null
        }
    }
}
