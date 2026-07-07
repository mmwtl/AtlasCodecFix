package com.mmwtl.atlascodecfix

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ErrorNotifier(
    private val context: Context,
    private val prefs: CodecFixPrefs
) {
    @SuppressLint("MissingPermission")
    fun notify(title: String, message: String) {
        if (!prefs.errorNotificationsEnabled) return

        ensureChannel()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message.take(SHORT_TEXT_LIMIT))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Codec fix errors",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    private companion object {
        private const val CHANNEL_ID = "codec_fix_errors"
        private const val NOTIFICATION_ID = 1001
        private const val SHORT_TEXT_LIMIT = 120
    }
}
