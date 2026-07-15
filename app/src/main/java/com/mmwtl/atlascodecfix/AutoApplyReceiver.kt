package com.mmwtl.atlascodecfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoApplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Ignoring unexpected auto apply action: ${intent.action}")
            return
        }

        val scheduled = AutoApplyScheduler.sync(context, resetRetries = true)
        if (!scheduled) {
            val app = context.applicationContext as CodecFixApp
            app.errorNotifier.notify(
                context.getString(R.string.app_name),
                context.getString(R.string.auto_apply_schedule_failed)
            )
        }
    }

    private companion object {
        private const val TAG = "AtlasCodecFix"
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
