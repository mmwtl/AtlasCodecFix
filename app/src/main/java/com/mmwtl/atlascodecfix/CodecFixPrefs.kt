package com.mmwtl.atlascodecfix

import android.content.Context

class CodecFixPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("codec_fix_prefs", Context.MODE_PRIVATE)

    var adbEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADB_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_ENABLED, value).apply()

    var adbPort: Int
        get() = prefs.getInt(KEY_ADB_PORT, 5555)
        set(value) = prefs.edit().putInt(KEY_ADB_PORT, value.coerceIn(1, 65535)).apply()

    var autoApply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_APPLY, value).apply()

    var selectedVariant: HevcCodecFixVariant
        get() = HevcCodecFixVariant.fromArgument(prefs.getString(KEY_SELECTED_VARIANT, null))
            ?: HevcCodecFixVariant.DEFAULT
        set(value) = prefs.edit().putString(KEY_SELECTED_VARIANT, value.argument).apply()

    private companion object {
        private const val KEY_ADB_ENABLED = "adb_enabled"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_AUTO_APPLY = "auto_apply"
        private const val KEY_SELECTED_VARIANT = "selected_variant"
    }
}
