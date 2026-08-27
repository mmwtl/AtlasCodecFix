package com.mmwtl.atlascodecfix

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TelnetShellDiscoveryTest {
    @Test
    fun preferredEndpointWorksWhenProcHasNoVisiblePorts() {
        val transport = FakeTransport()
        val discovery = TelnetShellDiscovery(
            markerFactory = { "__MARKER__:" },
            portSource = TelnetPortSource { emptyList() },
            connector = { host, port ->
                assertEquals("127.0.0.1", host)
                assertEquals(23_456, port)
                transport
            }
        )

        val (endpoint, connectedTransport) = discovery.open(
            preferredEndpoint = TelnetShellDiscovery.TelnetShellEndpoint("127.0.0.1", 23_456)
        )

        assertEquals(23_456, endpoint.port)
        assertTrue(connectedTransport === transport)
        assertTrue(transport.lastCommand.orEmpty().startsWith("printf "))
        assertFalse(transport.lastCommand.orEmpty().contains("pm path"))
    }

    @Test
    fun endpointCanAppearOnALaterDiscoveryAttempt() {
        var availablePort: Int? = null
        val discovery = TelnetShellDiscovery(
            markerFactory = { "__MARKER__:" },
            portSource = TelnetPortSource { host ->
                if (host == "127.0.0.1") listOfNotNull(availablePort) else emptyList()
            },
            connector = { _, _ -> FakeTransport() }
        )

        val firstFailure = assertThrows(IOException::class.java) {
            discovery.open(timeoutMs = 1_000)
        }
        assertTrue(firstFailure.message.orEmpty().contains("no local listening ports"))

        availablePort = 34_567
        val (endpoint) = discovery.open(timeoutMs = 1_000)
        assertEquals(34_567, endpoint.port)
    }

    @Test
    fun nonShellPortIsClosedAndReported() {
        val transport = FakeTransport(probeOutput = "not a shell")
        val discovery = TelnetShellDiscovery(
            markerFactory = { "__MARKER__:" },
            portSource = TelnetPortSource { host ->
                if (host == "127.0.0.1") listOf(45_678) else emptyList()
            },
            connector = { _, _ -> transport }
        )

        val failure = assertThrows(IOException::class.java) {
            discovery.open(timeoutMs = 1_000)
        }

        assertTrue(transport.closed)
        assertTrue(failure.message.orEmpty().contains("local ports checked"))
    }

    private class FakeTransport(
        private val probeOutput: String = "__ATLAS_TELNET_SHELL__"
    ) : TelnetCommandTransport {
        var lastCommand: String? = null
        var closed = false

        override fun isClosed(): Boolean = closed

        override fun exec(command: String, marker: String, timeoutMs: Long): Pair<String, Int> {
            lastCommand = command
            return probeOutput to 0
        }

        override fun close() {
            closed = true
        }
    }
}
