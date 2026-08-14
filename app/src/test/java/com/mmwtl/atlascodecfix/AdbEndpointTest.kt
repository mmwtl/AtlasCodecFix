package com.mmwtl.atlascodecfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbEndpointTest {
    @Test
    fun gInputBridgeEndpointModesUseTheExpectedPorts() {
        assertEquals(AdbEndpointMode.ATLAS, AdbEndpoint.modeForPort(5555))
        assertEquals(AdbEndpointMode.PREFACE, AdbEndpoint.modeForPort(7777))
        assertEquals(AdbEndpointMode.TELNET, AdbEndpoint.modeForPort(-667))
        assertEquals(AdbEndpointMode.CUSTOM, AdbEndpoint.modeForPort(6000))
    }

    @Test
    fun telnetSentinelIsValidButNegativeTcpPortsAreNotAccepted() {
        assertTrue(AdbEndpoint.isValidPort(AdbEndpoint.TELNET_PORT))
        assertTrue(AdbEndpoint.isValidPort(1))
        assertTrue(AdbEndpoint.isValidPort(65_535))
        assertFalse(AdbEndpoint.isValidPort(0))
        assertFalse(AdbEndpoint.isValidPort(-1))
        assertFalse(AdbEndpoint.isValidPort(65_536))
    }
}
