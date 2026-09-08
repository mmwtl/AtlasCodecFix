package com.mmwtl.atlascodecfix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoApplyRunGuardTest {
    @Test
    fun stoppedRunCannotFinishAfterReplacementStarts() {
        val guard = AutoApplyRunGuard()
        val first = guard.start()

        guard.invalidate()
        val replacement = guard.start()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(replacement))
    }

    @Test
    fun invalidatedRunCannotReportLateCompletion() {
        val guard = AutoApplyRunGuard()
        val run = guard.start()

        guard.invalidate()

        assertFalse(guard.isCurrent(run))
    }
}
