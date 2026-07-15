package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoApplyRetryPolicyTest {
    @Test
    fun retriesAreBoundedAcrossJobAndSystemStops() {
        assertEquals(1, AutoApplyRetryPolicy.nextRetryCount(0))
        assertEquals(4, AutoApplyRetryPolicy.nextRetryCount(3))
        assertNull(AutoApplyRetryPolicy.nextRetryCount(4))
    }

    @Test
    fun corruptedNegativeCountIsNormalized() {
        assertEquals(1, AutoApplyRetryPolicy.nextRetryCount(-100))
    }
}
