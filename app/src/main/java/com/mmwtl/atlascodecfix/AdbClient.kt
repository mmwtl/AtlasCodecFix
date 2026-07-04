package com.mmwtl.atlascodecfix

import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class AdbClient(
    private val prefs: CodecFixPrefs
) {
    private val lock = Mutex()
    private val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
    private val crypto: AdbCrypto by lazy { AdbCrypto.generateAdbKeyPair(base64) }
    private val host: String
        get() = if (BuildConfig.DEBUG) "10.0.2.2" else "localhost"

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connection: AdbConnection? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.adbEnabled) {
            closeAsDisconnected()
            return@withContext false
        }

        lock.withLock {
            if (isConnectedUnsafe()) {
                _connectionState.value = AdbConnectionState.Connected
                return@withLock true
            }

            _connectionState.value = AdbConnectionState.Connecting
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(host, prefs.adbPort), TIMEOUT_MS)

                val newConnection = AdbConnection.create(newSocket, crypto)
                newConnection.connect()

                socket = newSocket
                connection = newConnection
                _connectionState.value = AdbConnectionState.Connected
                true
            } catch (t: Throwable) {
                Log.w(TAG, "ADB connect failed", t)
                safeClose()
                _connectionState.value = AdbConnectionState.Error(t.message ?: "ADB connect error")
                false
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lock.withLock {
            safeClose()
            _connectionState.value = AdbConnectionState.Disconnected
        }
    }

    suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        if (!prefs.adbEnabled) {
            closeAsDisconnected()
            return@withContext "ADB helper disabled"
        }

        if (!isConnectedUnsafe()) {
            val connected = connect()
            if (!connected) return@withContext "ADB connect failed"
        }

        if (command.isBlank()) return@withContext "empty command"

        try {
            executeLocked(command)
        } catch (t: CommandFailedException) {
            Log.w(TAG, "ADB command failed: exit=${t.exitCode}, output=${t.output.takeLast(300)}", t)
            "ADB command failed (exit=${t.exitCode})\n${t.output}"
        } catch (t: Throwable) {
            Log.w(TAG, "ADB execute failed", t)
            val message = t.message ?: "ADB execute error"
            dropConnection(message)
            message
        }
    }

    private suspend fun closeAsDisconnected() {
        lock.withLock {
            safeClose()
            _connectionState.value = AdbConnectionState.Disconnected
        }
    }

    private suspend fun dropConnection(message: String) {
        lock.withLock {
            safeClose()
            _connectionState.value = AdbConnectionState.Error(message)
        }
    }

    private suspend fun executeLocked(command: String): String = lock.withLock {
        val conn = checkNotNull(connection) { "ADB is not connected" }
        val marker = DONE_PREFIX + System.nanoTime() + ":"
        val stream: AdbStream = conn.open("shell:${appendMarker(command, marker)}")

        try {
            val (output, exitCode) = readUntilMarker(stream, marker)
            if (exitCode != 0) {
                throw CommandFailedException(exitCode, output)
            }
            output
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun appendMarker(command: String, marker: String): String {
        val trimmed = command.trimEnd()
        return if (trimmed.endsWith(";")) {
            "$trimmed echo $marker\$?"
        } else {
            "$trimmed; echo $marker\$?"
        }
    }

    private fun readUntilMarker(stream: AdbStream, marker: String): Pair<String, Int> {
        val out = StringBuilder(256)
        var markerIndex = -1

        while (!stream.isClosed) {
            val chunk = stream.read() ?: break
            if (chunk.isEmpty()) break
            out.append(String(chunk))

            if (markerIndex < 0) {
                markerIndex = out.indexOf(marker)
            }
            if (markerIndex >= 0) {
                val after = out.substring(markerIndex + marker.length)
                val exitCode = parseLeadingInt(after)
                if (exitCode != null) {
                    return out.substring(0, markerIndex).trimEnd() to exitCode
                }
            }
        }

        throw IOException("ADB stream closed before completion marker")
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

    private fun isConnectedUnsafe(): Boolean {
        val activeSocket = socket
        return activeSocket != null && !activeSocket.isClosed && connection != null
    }

    private fun safeClose() {
        val oldConnection = connection
        val oldSocket = socket
        connection = null
        socket = null
        runCatching { oldConnection?.close() }
        runCatching { oldSocket?.close() }
    }

    private class CommandFailedException(
        val exitCode: Int,
        val output: String
    ) : IOException("ADB command failed (exit=$exitCode)")

    private companion object {
        private const val TAG = "AtlasCodecFixAdb"
        private const val TIMEOUT_MS = 5_000
        private const val DONE_PREFIX = "__ADB_DONE__:"
    }
}
