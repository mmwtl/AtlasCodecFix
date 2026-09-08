package com.mmwtl.atlascodecfix

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoApplyAccessibilityService : AccessibilityService() {
    private var lastWindowEventAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastWindowEventAtMs = SystemClock.elapsedRealtime()
        ensureAutoApplyScheduled("service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val now = SystemClock.elapsedRealtime()
        val previous = lastWindowEventAtMs
        lastWindowEventAtMs = now
        if (previous > 0L && now - previous >= RESUME_EVENT_GAP_MS) {
            val app = application as CodecFixApp
            if (app.prefs.selectedVariant == HevcCodecFixVariant.DEFAULT) return
            ensureAutoApplyScheduled("window event after idle")
        }
    }

    override fun onInterrupt() = Unit

    private fun ensureAutoApplyScheduled(reason: String) {
        if (!AutoApplyScheduler.ensureScheduled(this)) {
            Log.e(TAG, "Unable to schedule auto apply: $reason")
        }
    }

    private companion object {
        private const val TAG = "AtlasCodecFix"
        private const val RESUME_EVENT_GAP_MS = 60_000L
    }
}
