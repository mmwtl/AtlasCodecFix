package com.mmwtl.atlascodecfix

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.LinkedHashSet
import java.util.TreeSet

internal fun interface TelnetPortSource {
    fun listeningPorts(host: String): List<Int>
}

internal class TelnetShellDiscovery(
    private val markerFactory: () -> String,
    private val portSource: TelnetPortSource = ProcTelnetPortSource(),
    private val connector: (String, Int) -> TelnetCommandTransport = TelnetShellTransport::connect,
    private val nanoTime: () -> Long = System::nanoTime
) {
    @Volatile
    private var cachedEndpoint: TelnetShellEndpoint? = null

    fun open(
        preferredEndpoint: TelnetShellEndpoint? = null,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): Pair<TelnetShellEndpoint, TelnetCommandTransport> {
        val deadlineNanos = nanoTime() + timeoutMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND
        val candidates = LinkedHashSet<TelnetShellEndpoint>()
        cachedEndpoint?.let(candidates::add)
        preferredEndpoint?.takeIf { it.port in 1..65_535 }?.let(candidates::add)

        val scanErrors = mutableListOf<String>()
        for (host in candidateHosts()) {
            if (nanoTime() >= deadlineNanos) break
            runCatching { portSource.listeningPorts(host) }
                .onSuccess { ports ->
                    ports.filter { it in 1..65_535 }
                        .forEach { candidates += TelnetShellEndpoint(host, it) }
                }
                .onFailure { error ->
                    scanErrors += "$host: ${error.safeMessage()}"
                    Log.w(TAG, "Unable to inspect listening ports on $host", error)
                }
        }

        var probed = 0
        for (endpoint in candidates) {
            if (nanoTime() >= deadlineNanos) break
            probed++
            val transport = runCatching { connector(endpoint.host, endpoint.port) }
                .onFailure {
                    Log.d(TAG, "Telnet probe failed for ${endpoint.host}:${endpoint.port}", it)
                }
                .getOrNull() ?: continue
            if (isAndroidShell(transport, deadlineNanos)) {
                cachedEndpoint = endpoint
                return endpoint to transport
            }
            transport.close()
        }

        val scanSummary = scanErrors.take(MAX_REPORTED_SCAN_ERRORS).joinToString("; ")
        val reason = when {
            scanSummary.isNotBlank() -> "port scan failed: $scanSummary"
            candidates.isEmpty() -> "no local listening ports found"
            nanoTime() >= deadlineNanos -> "discovery timed out after ${timeoutMs}ms"
            else -> "$probed local ports checked"
        }
        throw IOException("Telnet shell endpoint not found ($reason)")
    }

    fun clearCache() {
        cachedEndpoint = null
    }

    private fun isAndroidShell(
        transport: TelnetCommandTransport,
        deadlineNanos: Long
    ): Boolean {
        val remainingMs = ((deadlineNanos - nanoTime()) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(1L)
        return runCatching {
            val (output, exitCode) = transport.exec(
                // Do not depend on PackageManager readiness during boot. Successfully parsing our
                // dynamic completion marker already proves that this endpoint is a command shell.
                command = "printf '$SHELL_PROBE\\n'",
                marker = markerFactory(),
                timeoutMs = remainingMs.coerceAtMost(VALIDATION_TIMEOUT_MS)
            )
            exitCode == 0 && output.lineSequence().any { it.trim() == SHELL_PROBE }
        }.getOrDefault(false)
    }

    private fun candidateHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        hosts += "127.0.0.1"
        hosts += "::1"
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    addresses.nextElement().hostAddress?.let(hosts::add)
                }
            }
        }.onFailure { Log.d(TAG, "Unable to enumerate local interfaces", it) }
        return hosts.toList()
    }

    internal data class TelnetShellEndpoint(val host: String, val port: Int)

    companion object {
        private const val VALIDATION_TIMEOUT_MS = 1_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_REPORTED_SCAN_ERRORS = 2
        private const val SHELL_PROBE = "__ATLAS_TELNET_SHELL__"
        private const val TAG = "AtlasCodecFixTelnet"
    }

    private fun Throwable.safeMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private class ProcTelnetPortSource : TelnetPortSource {
    override fun listeningPorts(host: String): List<Int> {
        val address = InetAddress.getByName(host)
        val ports = TreeSet<Int>()
        val failures = mutableListOf<Throwable>()
        if (address is Inet4Address) {
            runCatching {
                readProcTcp("/proc/net/tcp", ipv4ToProcHex(address), false, ports)
            }.onFailure(failures::add)
        }
        val ipv6Host = (address as? Inet6Address)?.let(::ipv6ToProcHex)
        runCatching { readProcTcp("/proc/net/tcp6", ipv6Host, true, ports) }
            .onFailure(failures::add)
        if (ports.isEmpty() && failures.isNotEmpty()) throw failures.first()
        // Prefer dynamic ports so the bounded discovery budget is not spent first on
        // well-known low-numbered system services.
        return ports.sortedDescending()
    }

    private fun readProcTcp(
        path: String,
        hostHex: String?,
        ipv6: Boolean,
        ports: MutableSet<Int>
    ) {
        val anyHex = if (ipv6) "00000000000000000000000000000000" else "00000000"
        File(path).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 4 || !fields[3].equals("0A", ignoreCase = true)) return@forEach
                val address = fields[1].split(':')
                if (address.size != 2) return@forEach
                val matches = address[0].equals(anyHex, ignoreCase = true) ||
                    (hostHex != null && address[0].equals(hostHex, ignoreCase = true))
                if (matches) address[1].toIntOrNull(16)?.let(ports::add)
            }
        }
    }

    private fun ipv4ToProcHex(address: Inet4Address): String {
        val bytes = address.address
        return bytes.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            .chunked(2).reversed().joinToString("")
    }

    private fun ipv6ToProcHex(address: Inet6Address): String {
        val bytes = address.address
        return buildString(32) {
            for (word in 0 until 4) {
                val base = word * 4
                for (index in 3 downTo 0) append("%02X".format(bytes[base + index].toInt() and 0xFF))
            }
        }
    }
}
