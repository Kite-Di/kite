package com.kite.di.board

import com.kite.di.graph.GraphJson
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The dependency board, served from the developer's machine.
 *
 * It owns the two halves nobody else can put together: the graph, which is a
 * build artifact sitting in `build/kite/graph.json` of every module, and the
 * runtime, which only a running app knows. The UI is served out of this jar's
 * resources, so using the board needs no npm, no node and no checkout of Kite.
 *
 * Bound to loopback only — it reads source paths and writes GraphRules.kt.
 */
class BoardServer(
    private val port: Int,
    private val store: GraphStore,
    private val repoRoot: File,
) {
    private val clients = CopyOnWriteArrayList<WebSocketSession>()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "kite-board").apply { isDaemon = true }
    }
    private val watcher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kite-board-watch").apply { isDaemon = true }
    }

    fun start() {
        // Raw socket accept loop: the JDK's HttpServer cannot hand over a
        // connection for a protocol upgrade, and the board needs one socket that
        // speaks WebSocket.
        val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        watcher.scheduleWithFixedDelay({ tick() }, WATCH_MS, WATCH_MS, TimeUnit.MILLISECONDS)
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            pool.execute { serve(socket) }
        }
    }

    /** A committed graph change drops the sockets; boards reconnect onto the new one. */
    private fun tick() {
        if (!store.refresh()) return
        for (client in clients) client.close()
        clients.clear()
    }

    // ---- runtime from a device -------------------------------------------------

    fun onRuntimeState(state: com.kite.di.graph.RuntimeState?) {
        store.runtime = state
        broadcastSnapshot()
    }

    /**
     * Forwards a device event under *this* server's sequence numbers. The board
     * watches for gaps to notice dropped messages, so an event that kept the
     * device's numbering — or carried none — reads as a gap, and the board answers
     * a gap with `resync`. Forever.
     */
    fun onRuntimeEvent(json: String) {
        broadcast(json)
    }

    private fun broadcastSnapshot() {
        val message = snapshotMessage()
        for (client in clients) if (client.open) client.sendStamped(message) else clients.remove(client)
    }

    private fun snapshotMessage(): String =
        """{"type":"graph.snapshot","snapshot":${GraphJson.encode(store.snapshot())}}"""

    private fun broadcast(message: String) {
        for (client in clients) {
            if (client.open) client.sendStamped(message) else clients.remove(client)
        }
    }

    // ---- connection handling ---------------------------------------------------

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        val request = readRequest(input) ?: run { socket.close(); return }

        if (request.headers["upgrade"]?.lowercase() == "websocket" && request.path == "/api/live") {
            upgrade(socket, input, output, request)
            return
        }
        runCatching { handleHttp(request, output) }
        runCatching { socket.close() }
    }

    private fun upgrade(socket: Socket, input: java.io.InputStream, output: OutputStream, request: Request) {
        val key = request.headers["sec-websocket-key"] ?: run { socket.close(); return }
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${WebSocketSession.acceptKey(key)}\r\n\r\n").toByteArray()
        )
        output.flush()

        val session = WebSocketSession(socket, input, output)
        clients += session
        val snapshot = store.snapshot()
        session.sendStamped(
            """{"type":"hello","schemaVersion":${snapshot.schemaVersion},""" +
                """"appId":${quote(snapshot.appId)},"variant":${quote(snapshot.variant)},""" +
                """"buildFingerprint":${quote(store.fingerprint)}}"""
        )
        session.sendStamped("""{"type":"graph.snapshot","snapshot":${GraphJson.encode(snapshot)}}""")
        onClientConnected?.invoke()

        session.readLoop { text ->
            when {
                text.contains("\"ping\"") -> session.sendStamped("""{"type":"pong"}""")
                text.contains("\"resync\"") -> {
                    onClientConnected?.invoke()
                    session.sendStamped(snapshotMessage())
                }
            }
        }
        clients.remove(session)
    }

    /** Set by the launcher: a fresh board asks the device for current runtime state. */
    var onClientConnected: (() -> Unit)? = null

    private fun handleHttp(request: Request, output: OutputStream) {
        when {
            request.path == "/api/graph" -> respondJson(output, GraphJson.encode(store.snapshot()))
            request.path == "/api/meta" -> {
                val snapshot = store.snapshot()
                respondJson(
                    output,
                    """{"appId":${quote(snapshot.appId)},"versionName":"dev",""" +
                        """"buildFingerprint":${quote(store.fingerprint)},"schemaVersion":${snapshot.schemaVersion}}"""
                )
            }
            request.path == "/api/decisions" -> {
                val decisions = store.decisions()
                respondJson(
                    output,
                    if (decisions == null) """{"module":"?","pending":[]}"""
                    else GraphJson.json.encodeToString(com.kite.di.graph.DecisionsFile.serializer(), decisions)
                )
            }
            request.path == "/api/decisions/apply" && request.method == "POST" -> {
                val (status, payload) = DecisionWriter(store, repoRoot).apply(request.body)
                respondJson(output, payload, status)
            }
            else -> respondStatic(output, request.path)
        }
    }

    // ---- static bundle out of this jar ----------------------------------------

    private fun respondStatic(output: OutputStream, path: String) {
        val clean = path.trimStart('/').ifEmpty { "index.html" }
        val bytes = readResource(clean) ?: readResource("index.html")
        if (bytes == null) {
            respond(output, 404, "text/plain; charset=utf-8", "Board bundle missing from the Kite plugin jar.".toByteArray())
            return
        }
        respond(output, 200, contentType(clean), bytes)
    }

    private fun readResource(path: String): ByteArray? =
        BoardServer::class.java.classLoader?.getResourceAsStream("board/$path")?.use { it.readBytes() }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun respondJson(output: OutputStream, json: String, status: Int = 200) {
        respond(output, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun respond(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val head = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    // ---- request parsing -------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: java.io.InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else ""
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }

    private fun readLine(input: java.io.InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(value.toChar())
        }
    }

    /** Minimal JSON string literal — the board only ever quotes ids and fingerprints. */
    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }

    private companion object {
        const val WATCH_MS = 700L
    }
}
