package com.mmwtl.atlascodecfix

/**
 * Values used by the ADB helper selector. The negative Telnet value is a UI/persistence sentinel;
 * it is never passed to Socket.connect().
 */
object AdbEndpoint {
    const val ATLAS_PORT = 5555
    const val PREFACE_PORT = 7777
    const val TELNET_PORT = -667

    fun isValidPort(port: Int): Boolean {
        return port == TELNET_PORT || port in 1..65_535
    }

    fun modeForPort(port: Int): AdbEndpointMode {
        return when (port) {
            ATLAS_PORT -> AdbEndpointMode.ATLAS
            PREFACE_PORT -> AdbEndpointMode.PREFACE
            TELNET_PORT -> AdbEndpointMode.TELNET
            else -> AdbEndpointMode.CUSTOM
        }
    }
}

enum class AdbEndpointMode {
    ATLAS,
    PREFACE,
    CUSTOM,
    TELNET
}
