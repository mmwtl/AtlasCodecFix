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

internal class TelnetShellDiscovery(private val markerFactory: () -> String) {
    @Volatile
    private var cachedEndpoint: TelnetShellEndpoint? = null

    fun open(): Pair<TelnetShellEndpoint, TelnetShellTransport> {
        val cached = cachedEndpoint
        if (cached != null) {
            val transport = runCatching {
                TelnetShellTransport.connect(cached.host, cached.port)
            }.getOrNull()
            if (transport != null) {
                if (isAndroidShell(transport)) {
                    transport.prepareQuietShell()
                    return cached to transport
                }
                transport.close()
            }
        }

        for (host in candidateHosts()) {
            for (port in listeningPorts(host)) {
                val transport = runCatching {
                    TelnetShellTransport.connect(host, port)
                }.getOrNull() ?: continue
                if (isAndroidShell(transport)) {
                    transport.prepareQuietShell()
                    val endpoint = TelnetShellEndpoint(host, port)
                    cachedEndpoint = endpoint
                    return endpoint to transport
                }
                transport.close()
            }
        }

        throw IOException("Telnet shell endpoint not found")
    }

    fun clearCache() {
        cachedEndpoint = null
    }

    private fun isAndroidShell(transport: TelnetShellTransport): Boolean {
        return runCatching {
            val (output, exitCode) = transport.exec(
                command = "pm path android",
                marker = markerFactory(),
                timeoutMs = VALIDATION_TIMEOUT_MS
            )
            exitCode == 0 && output.contains("package:")
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

    private fun listeningPorts(host: String): List<Int> {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return emptyList()
        val ports = TreeSet<Int>()
        if (address is Inet4Address) {
            runCatching {
                readProcTcp("/proc/net/tcp", ipv4ToProcHex(address), false, ports)
            }
        }
        val ipv6Host = (address as? Inet6Address)?.let(::ipv6ToProcHex)
        runCatching { readProcTcp("/proc/net/tcp6", ipv6Host, true, ports) }
        return ports.toList()
    }

    private fun readProcTcp(
        path: String,
        hostHex: String?,
        ipv6: Boolean,
        ports: MutableSet<Int>
    ) {
        val anyHex = if (ipv6) {
            "00000000000000000000000000000000"
        } else {
            "00000000"
        }
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
        return bytes.joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            .chunked(2)
            .reversed()
            .joinToString("")
    }

    private fun ipv6ToProcHex(address: Inet6Address): String {
        val bytes = address.address
        return buildString(32) {
            for (word in 0 until 4) {
                val base = word * 4
                for (index in 3 downTo 0) {
                    append("%02X".format(bytes[base + index].toInt() and 0xFF))
                }
            }
        }
    }

    internal data class TelnetShellEndpoint(val host: String, val port: Int)

    companion object {
        private const val VALIDATION_TIMEOUT_MS = 5_000L
        private const val TAG = "AtlasCodecFixTelnet"
    }
}
