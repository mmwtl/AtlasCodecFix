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
                    val message = "Auto apply skipped: ADB connect failed"
                    Log.w(TAG, message)
                    app.errorNotifier.notify("Atlas Codec Fix", message)
                    return@launch
                }

                val skipCompatibilityCheck = app.prefs.skipCompatibilityCheck
                if (!skipCompatibilityCheck) {
                    val compatibility = app.codecFixRepository.checkCompatibility()
                    if (!compatibility.autoApplyAllowed) {
                        val message = "Auto apply skipped, preflight ${compatibility.status}: " +
                            compatibility.output.trim().takeLast(180)
                        Log.w(
                            TAG,
                            message
                        )
                        app.errorNotifier.notify("Atlas Codec Fix", message)
                        return@launch
                    }
                }

                val currentVariant = app.codecFixRepository.detectCurrentVariant().variant
                if (currentVariant == app.prefs.selectedVariant) {
                    Log.i(TAG, "Auto apply skipped, already ${app.prefs.selectedVariant.argument}")
                    return@launch
                }

                val result = app.codecFixRepository.applyVariant(
                    variant = app.prefs.selectedVariant,
                    allowRisky = false,
                    skipCompatibilityCheck = skipCompatibilityCheck
                )
                Log.i(TAG, "Auto apply ${app.prefs.selectedVariant.argument}: success=${result.success}")
                if (!result.success) {
                    app.errorNotifier.notify(
                        "Atlas Codec Fix",
                        listOf(result.runOutput, result.detectOutput)
                            .joinToString("\n")
                            .trim()
                            .takeLast(180)
                            .ifBlank { "Auto apply failed for ${app.prefs.selectedVariant.argument}" }
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto apply failed", t)
                app.errorNotifier.notify("Atlas Codec Fix", t.message ?: t.javaClass.simpleName)
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
