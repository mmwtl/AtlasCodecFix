package com.mmwtl.atlascodecfix

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelnetShellTransportTest {
    @Test
    fun telnetShellReturnsOutputAndExitCodeFromCompletionMarker() {
        val server = ServerSocket(0)
        val setupCommand = AtomicReference<String?>(null)
        val worker = thread(start = true, name = "test-telnet-shell") {
            server.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream().bufferedWriter()

                val setup = input.readLine()
                setupCommand.set(setup)
                val setupMarker = setup.substringAfterLast("echo ").substringBefore("${'$'}?")
                output.write("${setupMarker}0\n")
                output.flush()

                val command = input.readLine()
                val marker = command.substringAfterLast("echo ").substringBefore("${'$'}?")
                output.write("codec-output\n${marker}0\n")
                output.flush()
            }
        }

        try {
            val transport = TelnetShellTransport.connect("127.0.0.1", server.localPort)
            try {
                transport.prepareQuietShell()
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

        assertTrue(setupCommand.get()?.contains("stty -echo") == true)
    }
}
