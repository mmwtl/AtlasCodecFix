package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecFixScreenRulesTest {
    @Test
    fun profileTabIsFirstAndTabOrderIsStable() {
        assertEquals(
            listOf(
                CodecFixTab.PROFILE,
                CodecFixTab.CONNECTION,
                CodecFixTab.AUTOSTART,
                CodecFixTab.DIAGNOSTICS
            ),
            CodecFixTab.entries.toList()
        )
    }

    @Test
    fun disabledAdbAlwaysPointsToConnectionTab() {
        listOf(
            AdbConnectionState.Connected,
            AdbConnectionState.Connecting,
            AdbConnectionState.Disconnected,
            AdbConnectionState.Error("boom")
        ).forEach { connectionState ->
            assertEquals(
                ProfileConnectionHint.ADB_DISABLED,
                CodecFixScreenRules.profileConnectionHint(adbEnabled = false, connectionState)
            )
        }
    }

    @Test
    fun enabledAdbHintsOnlyWhenConnectionIsMissing() {
        assertNull(
            CodecFixScreenRules.profileConnectionHint(true, AdbConnectionState.Connected)
        )
        assertNull(
            CodecFixScreenRules.profileConnectionHint(true, AdbConnectionState.Connecting)
        )
        assertEquals(
            ProfileConnectionHint.ADB_NOT_CONNECTED,
            CodecFixScreenRules.profileConnectionHint(true, AdbConnectionState.Disconnected)
        )
        assertEquals(
            ProfileConnectionHint.ADB_NOT_CONNECTED,
            CodecFixScreenRules.profileConnectionHint(true, AdbConnectionState.Error("refused"))
        )
    }

    @Test
    fun headerStatusIgnoresBlankText() {
        assertNull(CodecFixScreenRules.headerStatus(null))
        assertNull(CodecFixScreenRules.headerStatus(" \n "))
        assertEquals("Профиль применён: Min", CodecFixScreenRules.headerStatus(" Профиль применён: Min\n"))
    }
}
