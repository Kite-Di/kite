package com.kite.di.board

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * The few hundred bytes of RFC 6455 the board actually needs: a handshake and
 * text frames, one connection per browser tab.
 *
 * Hand-rolled on purpose. The alternative is putting a WebSocket library on the
 * Gradle plugin's classpath, where every extra jar is a version someone else's
 * build has to agree with — and this side of the protocol is a hash, a header and
 * a length prefix. No fragmentation, no compression, no binary frames: the board
 * sends JSON lines and receives `ping`/`resync`.
 */
class WebSocketSession(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val writeLock = Any()

    /**
     * Per-connection message numbering. The board notices dropped messages by
     * watching for gaps, so two tabs sharing one counter would each see half the
     * numbers missing and ask for a resync on every message.
     */
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    /** Sends a JSON object body (without `seq`), stamped for this connection. */
    fun sendStamped(body: String) {
        val inner = body.trim().removePrefix("{")
        send("{\"seq\":${seq.getAndIncrement()},$inner")
    }

    @Volatile
    var open: Boolean = true
        private set

    fun send(text: String) {
        if (!open) return
        val payload = text.toByteArray(Charsets.UTF_8)
        try {
            synchronized(writeLock) {
                output.write(0x81) // FIN + text opcode
                when {
                    payload.size < 126 -> output.write(payload.size)
                    payload.size <= 0xFFFF -> {
                        output.write(126)
                        output.write((payload.size shr 8) and 0xFF)
                        output.write(payload.size and 0xFF)
                    }
                    else -> {
                        output.write(127)
                        for (shift in 56 downTo 0 step 8) output.write((payload.size ushr shift) and 0xFF)
                    }
                }
                output.write(payload)
                output.flush()
            }
        } catch (e: Exception) {
            close()
        }
    }

    /** Blocks reading text frames until the peer goes away. Control frames are handled here. */
    fun readLoop(onText: (String) -> Unit) {
        try {
            while (open) {
                val first = input.read()
                if (first < 0) break
                val opcode = first and 0x0F
                val second = input.read()
                if (second < 0) break
                val masked = (second and 0x80) != 0
                var length = (second and 0x7F).toLong()
                if (length == 126L) {
                    length = ((input.readByteOrThrow() shl 8) or input.readByteOrThrow()).toLong()
                } else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or input.readByteOrThrow().toLong() }
                }
                val mask = ByteArray(4)
                if (masked) input.readFullyOrThrow(mask)
                val payload = ByteArray(length.toInt())
                input.readFullyOrThrow(payload)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

                when (opcode) {
                    0x1 -> onText(String(payload, Charsets.UTF_8))
                    0x8 -> break // close
                    0x9 -> sendPong(payload)
                    else -> Unit // pong or continuation: nothing the board needs
                }
            }
        } catch (e: Exception) {
            // a closed tab is the normal way out of this loop
        } finally {
            close()
        }
    }

    private fun sendPong(payload: ByteArray) {
        synchronized(writeLock) {
            output.write(0x8A)
            output.write(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    fun close() {
        open = false
        runCatching { socket.close() }
    }

    private fun InputStream.readByteOrThrow(): Int {
        val value = read()
        if (value < 0) throw java.io.EOFException()
        return value
    }

    private fun InputStream.readFullyOrThrow(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read < 0) throw java.io.EOFException()
            offset += read
        }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** The `Sec-WebSocket-Accept` value for a client's key. */
        fun acceptKey(clientKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest((clientKey + GUID).toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
