package com.mmwtl.atlascodecfix

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun telnetCommandsDoNotConsumeFollowingOutputAndNegotiationUsesCorrectRefusals() {
        val server = ServerSocket(0)
        val negotiationReply = AtomicReference<ByteArray>()
        val worker = thread(start = true, name = "test-telnet-negotiation") {
            server.accept().use { client ->
                val input = client.getInputStream()
                val command = input.bufferedReader().readLine()
                val marker = command.substringAfterLast("echo ").substringBefore("${'$'}?")
                val output = client.getOutputStream()
                output.write(byteArrayOf(0xFF.toByte(), 0xF1.toByte())) // IAC NOP
                output.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1)) // IAC WILL 1
                output.write(byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 2)) // IAC DO 2
                output.write(byteArrayOf(0xFF.toByte(), 0xFA.toByte(), 24)) // IAC SB
                output.write("ignored negotiation data".toByteArray())
                output.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()))
                output.write("payload\n${marker}0\n".toByteArray())
                output.flush()

                val reply = ByteArray(6)
                var offset = 0
                while (offset < reply.size) {
                    val count = input.read(reply, offset, reply.size - offset)
                    if (count < 0) break
                    offset += count
                }
                negotiationReply.set(reply.copyOf(offset))
            }
        }

        try {
            val transport = TelnetShellTransport.connect("127.0.0.1", server.localPort)
            try {
                val result = transport.exec("true", "__TEST_MARKER__:", 2_000)
                assertEquals("payload", result.first)
                assertEquals(0, result.second)
            } finally {
                transport.close()
            }
        } finally {
            worker.join(2_000)
            server.close()
        }

        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0xFE.toByte(), 1, // WILL -> DONT
                0xFF.toByte(), 0xFC.toByte(), 2 // DO -> WONT
            ),
            negotiationReply.get()
        )
    }

    @Test(timeout = 5_000)
    fun connectStopsAfterTotalBannerDeadlineWhenBannerKeepsTrickling() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val worker = thread(start = true, name = "test-telnet-banner") {
            server.accept().use { client ->
                accepted.countDown()
                val output = client.getOutputStream()
                try {
                    while (!client.isClosed) {
                        output.write('x'.code)
                        output.flush()
                        Thread.sleep(50)
                    }
                } catch (_: java.io.IOException) {
                    // The client closes the connection once its total banner deadline expires.
                }
            }
        }

        try {
            val startedAt = System.nanoTime()
            val transport = TelnetShellTransport.connect("127.0.0.1", server.localPort)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(accepted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("banner drain exceeded total deadline: ${elapsedMs}ms", elapsedMs < 2_000L)
            transport.close()
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
