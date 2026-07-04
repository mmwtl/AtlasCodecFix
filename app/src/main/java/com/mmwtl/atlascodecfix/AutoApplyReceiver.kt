package com.mmwtl.atlascodecfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoApplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as CodecFixApp

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!app.prefs.autoApply || !app.prefs.adbEnabled) return@launch

                if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
                ) {
                    delay(BOOT_APPLY_DELAY_MS)
                }

                val connected = app.adbClient.connect()
                if (!connected) {
                    Log.w(TAG, "Auto apply skipped: ADB connect failed")
                    return@launch
                }

                val compatibility = app.codecFixRepository.checkCompatibility()
                if (!compatibility.autoApplyAllowed) {
                    Log.w(
                        TAG,
                        "Auto apply skipped, preflight ${compatibility.status}: " +
                            compatibility.output.trim().takeLast(180)
                    )
                    return@launch
                }

                val currentVariant = app.codecFixRepository.detectCurrentVariant().variant
                if (currentVariant == app.prefs.selectedVariant) {
                    Log.i(TAG, "Auto apply skipped, already ${app.prefs.selectedVariant.argument}")
                    return@launch
                }

                val result = app.codecFixRepository.applyVariant(app.prefs.selectedVariant, allowRisky = false)
                Log.i(TAG, "Auto apply ${app.prefs.selectedVariant.argument}: success=${result.success}")
            } catch (t: Throwable) {
                Log.e(TAG, "Auto apply failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "AtlasCodecFix"
        private const val BOOT_APPLY_DELAY_MS = 12_000L
    }
}
