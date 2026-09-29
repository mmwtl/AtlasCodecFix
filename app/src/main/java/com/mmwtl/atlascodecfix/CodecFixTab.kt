package com.mmwtl.atlascodecfix

import androidx.annotation.StringRes

/** Main screen tabs. The primary action (profile selection and apply) stays first. */
enum class CodecFixTab(@param:StringRes val titleRes: Int) {
    PROFILE(R.string.tab_profile),
    CONNECTION(R.string.tab_connection),
    AUTOSTART(R.string.tab_autostart),
    DIAGNOSTICS(R.string.tab_diagnostics)
}

/** Why the profile tab should point the user to the connection tab. */
enum class ProfileConnectionHint(@param:StringRes val messageRes: Int) {
    ADB_DISABLED(R.string.profile_hint_adb_disabled),
    ADB_NOT_CONNECTED(R.string.profile_hint_adb_not_connected)
}

object CodecFixScreenRules {
    /**
     * Returns the one-line hint shown above the profile actions, or `null` when the connection is
     * usable or still being established. This only affects presentation; button availability keeps
     * using the view model state directly.
     */
    fun profileConnectionHint(
        adbEnabled: Boolean,
        connectionState: AdbConnectionState
    ): ProfileConnectionHint? = when {
        !adbEnabled -> ProfileConnectionHint.ADB_DISABLED
        connectionState == AdbConnectionState.Connected -> null
        connectionState == AdbConnectionState.Connecting -> null
        else -> ProfileConnectionHint.ADB_NOT_CONNECTED
    }

    /** Status text for the pinned header, or `null` when there is nothing to show. */
    fun headerStatus(status: String?): String? = status?.trim()?.takeIf(String::isNotEmpty)
}
