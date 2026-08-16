package com.mmwtl.atlascodecfix

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/** A small, bounded telnet shell transport used by the GInputBridge-compatible endpoint mode. */
internal class TelnetShellTransport private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream
) : Closeable {
    @Volatile
    private var closed = false

    fun isClosed(): Boolean = closed || socket.isClosed

    @Synchronized
    fun exec(command: String, marker: String, timeoutMs: Long): Pair<String, Int> {
        check(!isClosed()) { "Telnet is closed" }

        val effectiveCommand = if (command.trimEnd().endsWith(";")) {
            "${command.trimEnd()} echo $marker\$?"
        } else {
            "${command.trimEnd()}; echo $marker\$?"
        }
        output.write((effectiveCommand + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()

        val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND
        val collected = ByteArrayOutputStream(256)
        while (true) {
            val byte = readByteUntil(deadlineNanos)
            if (byte == -1) {
                throw IOException("Telnet stream closed before completion marker")
            }
            if (byte == IAC) {
                handleNegotiation(deadlineNanos)
                continue
            }
            if (byte == '\r'.code) continue
            collected.write(byte)

            val response = collected.toString(StandardCharsets.UTF_8.name())
            val markerIndex = response.lastIndexOf(marker)
            if (markerIndex < 0) continue

            val exitCode = parseLeadingInt(response.substring(markerIndex + marker.length))
                ?: continue
            drainTrailingOutput()
            return normalizeResponse(response.substring(0, markerIndex), marker) to exitCode
        }
    }

    override fun close() {
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    private fun readByteUntil(deadlineNanos: Long): Int {
        socket.soTimeout = readTimeoutMs(deadlineNanos)
        while (true) {
            try {
                return input.read()
            } catch (_: SocketTimeoutException) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw SocketTimeoutException("Telnet command timed out")
                }
            }
        }
    }

    private fun handleNegotiation(deadlineNanos: Long) {
        val command = readByteUntil(deadlineNanos)
        val option = readByteUntil(deadlineNanos)
        if (command == WILL || command == DO) {
            output.write(
                byteArrayOf(
                    IAC.toByte(),
                    if (command == WILL) WONT.toByte() else DONT.toByte(),
                    option.toByte()
                )
            )
            output.flush()
        }
    }

    private fun normalizeResponse(response: String, marker: String): String {
        var result = response.trimEnd('\n')
        val echoSuffix = "echo $marker\$?"
        val echoIndex = result.indexOf(echoSuffix)
        if (echoIndex >= 0) {
            val echoLineEnd = result.indexOf('\n', echoIndex + echoSuffix.length)
            result = if (echoLineEnd >= 0) result.substring(echoLineEnd + 1) else ""
        }
        return result.trim()
    }

    private fun readTimeoutMs(deadlineNanos: Long): Int {
        val remainingMs = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(1L)
        return remainingMs.coerceAtMost(READ_SLICE_TIMEOUT_MS.toLong()).toInt()
    }

    private fun drainTrailingOutput() {
        socket.soTimeout = TRAILING_DRAIN_TIMEOUT_MS
        while (true) {
            try {
                if (input.read() == -1) return
            } catch (_: SocketTimeoutException) {
                return
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val READ_SLICE_TIMEOUT_MS = 1_000
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val IAC = 0xFF
        private const val WILL = 0xFB
        private const val WONT = 0xFC
        private const val DO = 0xFD
        private const val DONT = 0xFE
        private const val TRAILING_DRAIN_TIMEOUT_MS = 200

        fun connect(host: String, port: Int): TelnetShellTransport {
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                drainBanner(socket, input, output)
                TelnetShellTransport(socket, input, output)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }

        private fun drainBanner(socket: Socket, input: InputStream, output: OutputStream) {
            socket.soTimeout = BANNER_DRAIN_TIMEOUT_MS
            while (true) {
                val first = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    return
                }
                if (first == -1) return
                if (first != IAC) continue

                val command = input.read()
                val option = input.read()
                if ((command == WILL || command == DO) && option >= 0) {
                    output.write(
                        byteArrayOf(
                            IAC.toByte(),
                            if (command == WILL) WONT.toByte() else DONT.toByte(),
                            option.toByte()
                        )
                    )
                    output.flush()
                }
            }
        }

        private fun parseLeadingInt(value: String): Int? {
            var index = 0
            while (index < value.length && value[index].isWhitespace()) index++
            if (index >= value.length || !value[index].isDigit()) return null

            var number = 0
            while (index < value.length && value[index].isDigit()) {
                number = number * 10 + (value[index] - '0')
                index++
            }
            return number
        }

        private const val BANNER_DRAIN_TIMEOUT_MS = 500
    }
}
