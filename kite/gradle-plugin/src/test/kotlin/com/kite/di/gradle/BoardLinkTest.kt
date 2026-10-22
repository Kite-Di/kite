package com.kite.di.gradle

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two things the board link does to the outside world: ask a port, kill a pid. */
class BoardLinkTest {

    private val started = mutableListOf<Process>()
    private val port = ServerSocket(0).use { it.localPort }

    @After
    fun cleanUp() {
        started.forEach { it.destroyForcibly() }
        BoardLink.pidFile(port).delete()
    }

    private fun spawn(vararg command: String): Process =
        ProcessBuilder(*command).start().also { started += it }

    @Test
    fun `asking a port that never answers does not hang the build`() {
        ServerSocket(port).use { silent ->
            // Accepts the connection, then says nothing — the way an unrelated service might.
            val accepting = Thread { runCatching { silent.accept() } }.apply { isDaemon = true; start() }
            val began = System.nanoTime()
            assertNull(BoardLink.servedAppId(port, timeoutMillis = 300))
            assertTrue((System.nanoTime() - began) / 1_000_000 < 3_000, "took too long")
            accepting.interrupt()
        }
    }

    @Test
    fun `a board answers with the app it serves`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/meta") { exchange ->
            val body = """{"appId":"com.example.app","versionName":"dev"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertEquals("com.example.app", BoardLink.servedAppId(port))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a pid the system gave to another process is left alone`() {
        val other = spawn("sleep", "30")
        BoardLink.pidFile(port).writeText(other.pid().toString())
        assertIs<BoardLink.Stopped.NotTheBoard>(BoardLink.stop(port))
        assertTrue(other.isAlive, "an unrelated process must not be killed")
    }

    @Test
    fun `the board's own process is stopped`() {
        // A stand-in whose command line names the board's entry point, as the real one's does.
        val board = spawn("sh", "-c", "sleep 30", "com.kite.di.board.BoardMain")
        BoardLink.pidFile(port).writeText(board.pid().toString())
        assertEquals(BoardLink.Stopped.Done(board.pid()), BoardLink.stop(port))
        assertTrue(board.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "the board must be gone")
    }

    @Test
    fun `nothing recorded, nothing stopped`() {
        assertEquals(BoardLink.Stopped.Nothing, BoardLink.stop(port))
    }
}
