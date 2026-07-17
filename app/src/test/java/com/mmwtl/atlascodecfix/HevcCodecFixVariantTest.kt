package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcCodecFixVariantTest {
    @Test
    fun manualInterfaceShowsEverySupportedVariant() {
        assertEquals(
            listOf(
                HevcCodecFixVariant.MSMNILE,
                HevcCodecFixVariant.MIN,
                HevcCodecFixVariant.DIREWOLF,
                HevcCodecFixVariant.MAX,
                HevcCodecFixVariant.ULTRA
            ),
            HevcCodecFixVariant.USER_VISIBLE
        )
    }

    @Test
    fun restoredAggregateVariantsRemainExperimental() {
        assertTrue(HevcCodecFixVariant.DIREWOLF.experimental)
        assertTrue(HevcCodecFixVariant.ULTRA.experimental)
    }
}
