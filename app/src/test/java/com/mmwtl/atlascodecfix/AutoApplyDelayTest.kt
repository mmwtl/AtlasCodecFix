package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoApplyDelayTest {
    @Test
    fun delayInputAcceptsOnlyConfiguredRange() {
        assertEquals(0, AutoApplyDelay.parse("0"))
        assertEquals(3_600, AutoApplyDelay.parse("3600"))
        assertNull(AutoApplyDelay.parse(""))
        assertNull(AutoApplyDelay.parse("-1"))
        assertNull(AutoApplyDelay.parse("3601"))
    }

    @Test
    fun scheduledDelayIsBoundedAndConvertedWithoutOverflow() {
        assertEquals(0L, AutoApplyDelay.toMilliseconds(-100))
        assertEquals(12_000L, AutoApplyDelay.toMilliseconds(12))
        assertEquals(3_600_000L, AutoApplyDelay.toMilliseconds(Int.MAX_VALUE))
    }
}
