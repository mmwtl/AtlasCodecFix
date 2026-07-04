package com.mmwtl.atlascodecfix

sealed interface AdbConnectionState {
    data object Connected : AdbConnectionState
    data object Disconnected : AdbConnectionState
    data object Connecting : AdbConnectionState
    data class Error(val message: String) : AdbConnectionState
}
