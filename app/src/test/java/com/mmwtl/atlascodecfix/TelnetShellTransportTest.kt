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
                val command = client.getInputStream().bufferedReader().readLine()
                val marker = command.substringAfter("echo ").substringBefore("${'$'}?")
                client.getOutputStream().bufferedWriter().use { output ->
                    output.write("codec-output\n${marker}0\n")
                    output.flush()
                }
            }
        }

        try {
            val transport = TelnetShellTransport.connect("127.0.0.1", server.localPort)
            try {
                val result = transport.exec("true", "__TEST_MARKER__:", 2_000)
                assertEquals("codec-output", result.first)
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
