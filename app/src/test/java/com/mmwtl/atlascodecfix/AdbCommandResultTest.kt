package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdbCommandResultTest {
    @Test
    fun successfulCommandUsesExitCodeInsteadOfOutputText() {
        val result = AdbCommandResult(
            stdout = "ADB connect failed is only payload text",
            exitCode = 0
        )

        assertTrue(result.succeeded)
        assertNull(result.failure)
        assertEquals("ADB connect failed is only payload text", result.displayOutput)
    }

    @Test
    fun nonZeroExitIsStructuredFailure() {
        val result = AdbCommandResult(stdout = "mount failed", exitCode = 12)

        assertFalse(result.succeeded)
        assertEquals("ADB command failed (exit=12)\nmount failed", result.displayOutput)
    }

    @Test
    fun transportFailureKeepsKind() {
        val result = AdbCommandResult.failure(
            AdbCommandFailureKind.TIMEOUT,
            "timed out"
        )

        assertFalse(result.succeeded)
        assertEquals(AdbCommandFailureKind.TIMEOUT, result.failure?.kind)
        assertEquals("timed out", result.displayOutput)
    }

    @Test
    fun shellMarkerHelpersHandleWhitespaceAndExistingSemicolon() {
        assertEquals("true; echo marker:$?", AdbClient.appendMarker("true;", "marker:"))
        assertEquals("true; echo marker:$?", AdbClient.appendMarker("true", "marker:"))
        assertEquals(17, AdbClient.parseLeadingInt(" \r\n17 trailing"))
        assertNull(AdbClient.parseLeadingInt("no exit"))
    }

    @Test
    fun timeoutMessageKeepsLastDiagnosticOutput() {
        val message = AdbClient.timeoutMessage(
            timeoutMs = 45_000,
            partialOutput = "phase:root_check\nphase:file_check"
        )

        assertEquals(
            "ADB command timed out after 45s\nLast output:\nphase:root_check\nphase:file_check",
            message
        )
    }

    @Test
    fun commandDeadlinePhysicallyClosesTransport() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val closed = CountDownLatch(1)
        val transport = Closeable { closed.countDown() }
        val deadline = AdbCommandDeadline(
            timeoutMs = 20,
            onTimeout = transport::close,
            scheduler = scheduler
        )

        try {
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(deadline.timedOut)
        } finally {
            deadline.close()
            scheduler.shutdownNow()
        }
    }
}
