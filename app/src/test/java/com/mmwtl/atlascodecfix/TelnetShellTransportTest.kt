package com.mmwtl.atlascodecfix

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class TelnetShellTransportTest {
    @Test
    fun telnetShellReturnsOutputAndExitCodeFromCompletionMarker() {
        val server = ServerSocket(0)
        val worker = thread(start = true, name = "test-telnet-shell") {
            server.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream().bufferedWriter()

                val command = input.readLine()
                val marker = command.substringAfterLast("echo ").substringBefore("${'$'}?")
                output.write("su root sh -c 'set -e\n")
                output.write("> sh \"${'$'}TARGET\"'; echo ${marker}${'$'}?\n")
                output.write("hevc_preflight:1\nphase:complete\n${marker}0\n")
                output.flush()
            }
        }

        try {
            val transport = TelnetShellTransport.connect("127.0.0.1", server.localPort)
            try {
                val result = transport.exec("true", "__TEST_MARKER__:", 2_000)
                assertEquals("hevc_preflight:1\nphase:complete", result.first)
                assertEquals(0, result.second)
            } finally {
                transport.close()
            }
        } finally {
            worker.join(2_000)
            server.close()
        }
    }
}
