package com.mmwtl.atlascodecfix

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class AdbClient(
    context: Context,
    private val prefs: CodecFixPrefs
) : AdbCommandExecutor {
    private val lock = Mutex()
    private val reconnectMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionGuard = Any()
    private val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
    private val keyStore = AdbKeyStore(File(context.noBackupFilesDir, KEY_DIR_NAME), base64)
    private val crypto: AdbCrypto by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        keyStore.loadOrCreate()
    }
    private val telnetDiscovery by lazy { TelnetShellDiscovery(::buildDoneMarker) }

    private val _connectionState =
        MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var telnetTransport: TelnetShellTransport? = null

    @Volatile
    private var connectedEndpoint: AdbEndpointSnapshot? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var manuallyDisconnected = false

    @Volatile
    private var connectionEpoch = 0L

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.adbEnabled) {
            disconnect()
            return@withContext false
        }

        manuallyDisconnected = false
        val endpoint = prefs.endpointSnapshot()
        val connected = if (endpoint.port == AdbEndpoint.TELNET_PORT) {
            connectTelnet(endpoint)
        } else {
            connectAdb(endpoint)
        }
        if (!connected) scheduleReconnect(endpoint)
        connected
    }

    suspend fun reconnect(): Boolean {
        disconnect()
        return connect()
    }

    /** Disconnects immediately, including when a command is blocked in a library read. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        manuallyDisconnected = true
        cancelReconnectLoop()
        synchronized(connectionGuard) {
            connectionEpoch++
        }
        forceCloseNow()
        _connectionState.value = AdbConnectionState.Disconnected
    }

    override suspend fun execute(
        command: String,
        timeoutMs: Long
    ): AdbCommandResult = withContext(Dispatchers.IO) {
        if (!prefs.adbEnabled) {
            disconnect()
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
        if (manuallyDisconnected) {
            return@withContext AdbCommandResult.failure(
                AdbCommandFailureKind.CONNECT,
                "ADB disconnected"
            )
        }

        var endpoint = prefs.endpointSnapshot()
        if (!isConnectedFor(endpoint) && !connect()) {
            val message = (_connectionState.value as? AdbConnectionState.Error)?.message
                ?: "ADB connect failed"
            return@withContext AdbCommandResult.failure(AdbCommandFailureKind.CONNECT, message)
        }
        // The endpoint can change while connect() is in flight. Use the current value for the
        // transport branch and the reconnect key, while keeping UI changes serialized by busy state.
        endpoint = prefs.endpointSnapshot()

        try {
            val result = if (endpoint.port == AdbEndpoint.TELNET_PORT) {
                executeTelnetLocked(command, timeoutMs)
            } else {
                executeAdbLocked(command, timeoutMs)
            }
            if (result.failure?.kind == AdbCommandFailureKind.TIMEOUT) {
                scheduleReconnect(endpoint)
            }
            result
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "ADB execute failed", t)
            val message = t.safeMessage("ADB execute error")
            dropConnection(message)
            scheduleReconnect(endpoint)
            AdbCommandResult.failure(AdbCommandFailureKind.TRANSPORT, message)
        }
    }

    private suspend fun connectAdb(endpoint: AdbEndpointSnapshot): Boolean =
        withContext(Dispatchers.IO) {
            var shouldReconnect = false
            val connected = lock.withLock {
                val myEpoch = synchronized(connectionGuard) { connectionEpoch }
                if (isConnectedFor(endpoint)) return@withLock true
                closeTransportLocked()
                _connectionState.value = AdbConnectionState.Connecting

                var newSocket: Socket? = null
                try {
                    newSocket = Socket()
                    newSocket.connect(
                        InetSocketAddress(endpoint.host, endpoint.port),
                        SOCKET_CONNECT_TIMEOUT_MS
                    )
                    val activeSocket = newSocket
                    synchronized(connectionGuard) {
                        // Publish the physical socket before the handshake so disconnect() can
                        // interrupt a blocking library handshake as well.
                        socket = activeSocket
                    }
                    val createdConnection = AdbConnection.create(activeSocket, crypto)
                    val handshakeComplete = runInterruptible(Dispatchers.IO) {
                        createdConnection.connect(
                            ADB_HANDSHAKE_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS,
                            false
                        )
                    }
                    if (!handshakeComplete) {
                        throw IOException(
                            "ADB handshake timed out after ${ADB_HANDSHAKE_TIMEOUT_MS / 1000}s"
                        )
                    }

                    val canPublish = synchronized(connectionGuard) {
                        connectionEpoch == myEpoch && !manuallyDisconnected
                    }
                    if (!canPublish) {
                        runCatching { activeSocket.close() }
                        synchronized(connectionGuard) {
                            if (socket === activeSocket) socket = null
                        }
                        return@withLock false
                    }

                    synchronized(connectionGuard) {
                        if (connectionEpoch != myEpoch || manuallyDisconnected) {
                            runCatching { activeSocket.close() }
                            if (socket === activeSocket) socket = null
                            return@withLock false
                        }
                        connection = createdConnection
                        socket = activeSocket
                        telnetTransport = null
                        connectedEndpoint = endpoint
                    }
                    _connectionState.value = AdbConnectionState.Connected
                    cancelReconnectLoop()
                    true
                } catch (t: CancellationException) {
                    runCatching { newSocket?.close() }
                    synchronized(connectionGuard) {
                        if (socket === newSocket) socket = null
                    }
                    throw t
                } catch (t: Throwable) {
                    shouldReconnect = true
                    runCatching { newSocket?.close() }
                    synchronized(connectionGuard) {
                        if (socket === newSocket) socket = null
                    }
                    Log.w(TAG, "ADB connect failed", t)
                    _connectionState.value =
                        AdbConnectionState.Error(t.safeMessage("ADB connect error"))
                    false
                }
            }
            if (!connected && shouldReconnect) scheduleReconnect(endpoint)
            connected
        }

    private suspend fun connectTelnet(endpoint: AdbEndpointSnapshot): Boolean =
        withContext(Dispatchers.IO) {
            var shouldReconnect = false
            val connected = lock.withLock {
                val myEpoch = synchronized(connectionGuard) { connectionEpoch }
                if (isConnectedFor(endpoint)) return@withLock true
                closeTransportLocked()
                _connectionState.value = AdbConnectionState.Connecting
                try {
                    val (discoveredEndpoint, transport) = telnetDiscovery.open()
                    val canPublish = synchronized(connectionGuard) {
                        connectionEpoch == myEpoch && !manuallyDisconnected
                    }
                    if (!canPublish) {
                        transport.close()
                        return@withLock false
                    }
                    synchronized(connectionGuard) {
                        if (connectionEpoch != myEpoch || manuallyDisconnected) {
                            transport.close()
                            return@withLock false
                        }
                        connection = null
                        socket = null
                        telnetTransport = transport
                        connectedEndpoint = endpoint.copy(host = discoveredEndpoint.host)
                    }
                    _connectionState.value = AdbConnectionState.Connected
                    cancelReconnectLoop()
                    true
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    shouldReconnect = true
                    telnetDiscovery.clearCache()
                    Log.w(TAG, "Telnet connect failed", t)
                    _connectionState.value =
                        AdbConnectionState.Error(t.safeMessage("Telnet connect error"))
                    false
                }
            }
            if (!connected && shouldReconnect) scheduleReconnect(endpoint)
            connected
        }

    private suspend fun executeAdbLocked(
        command: String,
        timeoutMs: Long
    ): AdbCommandResult = lock.withLock {
        val (conn, myEpoch) = synchronized(connectionGuard) {
            checkNotNull(connection) { "ADB is not connected" } to connectionEpoch
        }
        val commandSocket = checkNotNull(socket) { "ADB socket is not connected" }
        val marker = buildDoneMarker()
        val partialOutput = StringBuffer(256)
        val deadline = AdbCommandDeadline(
            timeoutMs = timeoutMs,
            onTimeout = { runCatching { commandSocket.close() } }
        )
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
            synchronized(connectionGuard) {
                if (connectionEpoch != myEpoch || manuallyDisconnected) {
                    throw IOException("ADB disconnected")
                }
            }
            if (deadline.timedOut) timeoutFailure(timeoutMs, partialOutput.toString()) else result
        } catch (t: CancellationException) {
            deadline.close()
            runCatching { commandSocket.close() }
            forceCloseNow()
            throw t
        } catch (t: Throwable) {
            deadline.close()
            if (!deadline.timedOut) throw t
            timeoutFailure(timeoutMs, partialOutput.toString())
        }
    }

    private suspend fun executeTelnetLocked(
        command: String,
        timeoutMs: Long
    ): AdbCommandResult = lock.withLock {
        val (transport, myEpoch) = synchronized(connectionGuard) {
            checkNotNull(telnetTransport) { "Telnet is not connected" } to connectionEpoch
        }
        val deadline = AdbCommandDeadline(timeoutMs = timeoutMs, onTimeout = transport::close)
        try {
            val (output, exitCode) = runInterruptible(Dispatchers.IO) {
                transport.exec(command, buildDoneMarker(), timeoutMs)
            }
            deadline.close()
            synchronized(connectionGuard) {
                if (connectionEpoch != myEpoch || manuallyDisconnected) {
                    throw IOException("Telnet disconnected")
                }
            }
            if (deadline.timedOut) {
                timeoutFailure(timeoutMs, output)
            } else {
                AdbCommandResult(stdout = output, exitCode = exitCode)
            }
        } catch (t: CancellationException) {
            deadline.close()
            transport.close()
            forceCloseNow()
            throw t
        } catch (t: Throwable) {
            deadline.close()
            if (t is SocketTimeoutException) {
                return@withLock timeoutFailure(timeoutMs, "")
            }
            if (!deadline.timedOut) throw t
            timeoutFailure(timeoutMs, "")
        }
    }

    private fun timeoutFailure(timeoutMs: Long, partialOutput: String): AdbCommandResult {
        val message = timeoutMessage(timeoutMs, partialOutput)
        Log.w(TAG, message)
        forceCloseNow()
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
            if (markerIndex < 0) markerIndex = out.indexOf(marker)
            if (markerIndex >= 0) {
                val after = out.substring(markerIndex + marker.length)
                parseLeadingInt(after)?.let { exitCode ->
                    return out.substring(0, markerIndex).trimEnd() to exitCode
                }
            }
        }
        throw IOException("ADB stream closed before completion marker")
    }

    private suspend fun dropConnection(message: String) {
        synchronized(connectionGuard) { connectionEpoch++ }
        forceCloseNow()
        if (!manuallyDisconnected) _connectionState.value = AdbConnectionState.Error(message)
    }

    private suspend fun scheduleReconnect(failedEndpoint: AdbEndpointSnapshot) {
        if (manuallyDisconnected || !prefs.adbEnabled) return
        reconnectMutex.withLock {
            if (reconnectJob?.isActive == true) return
            reconnectJob = ioScope.launch {
                var attempt = 0
                while (isActive && attempt < MAX_RECONNECT_RETRIES) {
                    if (manuallyDisconnected || !prefs.adbEnabled) break
                    val currentEndpoint = prefs.endpointSnapshot()
                    if (currentEndpoint.port != failedEndpoint.port ||
                        currentEndpoint.host != failedEndpoint.host
                    ) break
                    attempt++
                    delay(RECONNECT_DELAY_MS)
                    if (connect()) break
                }
                reconnectMutex.withLock { reconnectJob = null }
            }
        }
    }

    private fun cancelReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun isConnectedFor(endpoint: AdbEndpointSnapshot): Boolean {
        val activeEndpoint = connectedEndpoint
        if (_connectionState.value != AdbConnectionState.Connected ||
            activeEndpoint?.port != endpoint.port ||
            (endpoint.port != AdbEndpoint.TELNET_PORT && activeEndpoint.host != endpoint.host)
        ) {
            return false
        }
        return if (endpoint.port == AdbEndpoint.TELNET_PORT) {
            telnetTransport?.let { !it.isClosed() } == true
        } else {
            socket?.let { !it.isClosed } == true && connection != null
        }
    }

    private fun closeTransportLocked() {
        forceCloseNow()
    }

    private fun forceCloseNow() {
        val oldSocket: Socket?
        val oldTransport: TelnetShellTransport?
        synchronized(connectionGuard) {
            oldSocket = socket
            oldTransport = telnetTransport
            connection = null
            socket = null
            telnetTransport = null
            connectedEndpoint = null
        }
        // Closing the socket/stream is intentional: AdbConnection.close() waits unboundedly for
        // its reader thread in some library versions.
        runCatching { oldSocket?.close() }
        runCatching { oldTransport?.close() }
    }

    private fun buildDoneMarker(): String = DONE_PREFIX + System.nanoTime() + ":"

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
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_RETRIES = 5
    }

    private data class AdbEndpointSnapshot(val host: String, val port: Int)

    private fun CodecFixPrefs.endpointSnapshot(): AdbEndpointSnapshot {
        return AdbEndpointSnapshot(adbHost, adbPort)
    }
}
