package com.mmwtl.atlascodecfix

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class AdbClient(
    context: Context,
    private val prefs: CodecFixPrefs
) : AdbCommandExecutor {
    private val lock = Mutex()
    private val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
    private val keyStore = AdbKeyStore(File(context.noBackupFilesDir, KEY_DIR_NAME), base64)
    private val crypto: AdbCrypto by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        keyStore.loadOrCreate()
    }

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
                return@withLock true
            }

            _connectionState.value = AdbConnectionState.Connecting
            try {
                val newSocket = Socket()
                socket = newSocket
                newSocket.connect(InetSocketAddress(prefs.adbHost, prefs.adbPort), SOCKET_CONNECT_TIMEOUT_MS)

                val newConnection = AdbConnection.create(newSocket, crypto)
                connection = newConnection
                val handshakeComplete = runInterruptible(Dispatchers.IO) {
                    newConnection.connect(
                        ADB_HANDSHAKE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS,
                        false
                    )
                }
                if (!handshakeComplete) {
                    throw IOException("ADB handshake timed out after ${ADB_HANDSHAKE_TIMEOUT_MS / 1000}s")
                }

                _connectionState.value = AdbConnectionState.Connected
                true
            } catch (t: CancellationException) {
                safeClose()
                _connectionState.value = AdbConnectionState.Disconnected
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "ADB connect failed", t)
                safeClose()
                _connectionState.value = AdbConnectionState.Error(t.safeMessage("ADB connect error"))
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

    override suspend fun execute(
        command: String,
        timeoutMs: Long
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        if (!prefs.adbEnabled) {
            closeAsDisconnected()
            return@withContext AdbCommandResult.failure(
                AdbCommandFailureKind.DISABLED,
                "ADB helper disabled"
            )
        }

        if (command.isBlank()) {
            return@withContext AdbCommandResult.failure(
                AdbCommandFailureKind.INVALID_COMMAND,
                "Empty ADB command"
            )
        }

        if (!isConnectedUnsafe() && !connect()) {
            val message = (_connectionState.value as? AdbConnectionState.Error)?.message
                ?: "ADB connect failed"
            return@withContext AdbCommandResult.failure(AdbCommandFailureKind.CONNECT, message)
        }

        try {
            executeLocked(command, timeoutMs)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "ADB execute failed", t)
            val message = t.safeMessage("ADB execute error")
            dropConnection(message)
            AdbCommandResult.failure(AdbCommandFailureKind.TRANSPORT, message)
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

    private suspend fun executeLocked(
        command: String,
        timeoutMs: Long
    ): AdbCommandResult = lock.withLock {
        val conn = checkNotNull(connection) { "ADB is not connected" }
        val commandSocket = checkNotNull(socket) { "ADB socket is not connected" }
        val marker = DONE_PREFIX + System.nanoTime() + ":"
        val partialOutput = StringBuffer(256)
        val deadline = AdbCommandDeadline(timeoutMs, onTimeout = {
            runCatching { commandSocket.close() }
        })

        try {
            val result = runInterruptible(Dispatchers.IO) {
                val stream: AdbStream = conn.open("shell:${appendMarker(command, marker)}")
                try {
                    val (output, exitCode) = readUntilMarker(stream, marker, partialOutput)
                    AdbCommandResult(stdout = output, exitCode = exitCode)
                } finally {
                    runCatching { stream.close() }
                }
            }

            deadline.close()
            if (deadline.timedOut) {
                timeoutFailure(timeoutMs, partialOutput)
            } else {
                result
            }
        } catch (t: CancellationException) {
            deadline.close()
            runCatching { commandSocket.close() }
            safeClose()
            throw t
        } catch (t: Throwable) {
            deadline.close()
            if (!deadline.timedOut) throw t
            timeoutFailure(timeoutMs, partialOutput)
        }
    }

    private fun timeoutFailure(timeoutMs: Long, partialOutput: StringBuffer): AdbCommandResult {
        val message = timeoutMessage(timeoutMs, partialOutput.toString())
        Log.w(TAG, message)
        safeClose()
        _connectionState.value = AdbConnectionState.Error(message)
        return AdbCommandResult.failure(AdbCommandFailureKind.TIMEOUT, message)
    }

    private fun readUntilMarker(
        stream: AdbStream,
        marker: String,
        out: StringBuffer
    ): Pair<String, Int> {
        var markerIndex = -1

        while (!stream.isClosed) {
            val chunk = stream.read() ?: break
            if (chunk.isEmpty()) break
            out.append(String(chunk, Charsets.UTF_8))

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

    private fun isConnectedUnsafe(): Boolean {
        val activeSocket = socket
        return _connectionState.value == AdbConnectionState.Connected &&
            activeSocket != null &&
            !activeSocket.isClosed &&
            connection != null
    }

    private fun safeClose() {
        val oldSocket = socket
        connection = null
        socket = null
        // AdbConnection.close() performs an unbounded Thread.join(). Closing the real socket is
        // sufficient to stop its reader thread and, unlike join(), keeps disconnect/timeout finite.
        runCatching { oldSocket?.close() }
    }

    private fun Throwable.safeMessage(fallback: String): String {
        return message?.takeIf(String::isNotBlank) ?: fallback
    }

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 45_000L

        internal fun appendMarker(command: String, marker: String): String {
            val trimmed = command.trimEnd()
            return if (trimmed.endsWith(";")) {
                "$trimmed echo $marker\$?"
            } else {
                "$trimmed; echo $marker\$?"
            }
        }

        internal fun parseLeadingInt(value: String): Int? {
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

        internal fun timeoutMessage(timeoutMs: Long, partialOutput: String): String {
            val headline = "ADB command timed out after ${timeoutMs / 1000}s"
            val partial = partialOutput.trim().takeLast(MAX_TIMEOUT_OUTPUT_CHARS)
            return if (partial.isBlank()) headline else "$headline\nLast output:\n$partial"
        }

        private const val TAG = "AtlasCodecFixAdb"
        private const val KEY_DIR_NAME = "adb"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        private const val ADB_HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val DONE_PREFIX = "__ADB_DONE__:"
        private const val MAX_TIMEOUT_OUTPUT_CHARS = 2_000
    }
}
