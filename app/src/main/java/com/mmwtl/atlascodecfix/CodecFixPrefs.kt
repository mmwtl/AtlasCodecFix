package com.mmwtl.atlascodecfix

import android.content.Context
import androidx.core.content.edit

class CodecFixPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("codec_fix_prefs", Context.MODE_PRIVATE)

    var adbEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADB_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ADB_ENABLED, value) }

    var adbPort: Int
        get() = prefs.getInt(KEY_ADB_PORT, 5555)
        set(value) = prefs.edit { putInt(KEY_ADB_PORT, value.coerceIn(1, 65535)) }

    var adbHost: String
        get() = prefs.getString(KEY_ADB_HOST, DEFAULT_ADB_HOST)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_ADB_HOST
        set(value) = prefs.edit { putString(KEY_ADB_HOST, value.trim().ifEmpty { DEFAULT_ADB_HOST }) }

    var autoApply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_APPLY, value) }

    var skipCompatibilityCheck: Boolean
        get() = prefs.getBoolean(KEY_SKIP_COMPATIBILITY_CHECK, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_COMPATIBILITY_CHECK, value) }

    var errorNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ERROR_NOTIFICATIONS_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ERROR_NOTIFICATIONS_ENABLED, value) }

    var selectedVariant: HevcCodecFixVariant
        get() = HevcCodecFixVariant.fromArgument(prefs.getString(KEY_SELECTED_VARIANT, null))
            ?: HevcCodecFixVariant.DEFAULT
        set(value) = prefs.edit { putString(KEY_SELECTED_VARIANT, value.argument) }

    internal var autoApplyRetryCount: Int
        get() = prefs.getInt(KEY_AUTO_APPLY_RETRY_COUNT, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_AUTO_APPLY_RETRY_COUNT, value.coerceAtLeast(0)) }

    private companion object {
        private const val KEY_ADB_ENABLED = "adb_enabled"
        private const val KEY_ADB_HOST = "adb_host"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_AUTO_APPLY = "auto_apply"
        private const val KEY_SKIP_COMPATIBILITY_CHECK = "skip_compatibility_check"
        private const val KEY_ERROR_NOTIFICATIONS_ENABLED = "error_notifications_enabled"
        private const val KEY_SELECTED_VARIANT = "selected_variant"
        private const val KEY_AUTO_APPLY_RETRY_COUNT = "auto_apply_retry_count"
        private const val DEFAULT_ADB_HOST = "localhost"
    }
}
