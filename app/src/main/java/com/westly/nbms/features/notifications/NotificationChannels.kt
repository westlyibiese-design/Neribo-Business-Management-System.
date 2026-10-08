package com.westly.nbms.features.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** The one Android notification channel NBMS uses. The `send-push` function names the same id. */
object NotificationChannels {
    const val DEFAULT_CHANNEL_ID = "nbms_default"
    const val DEFAULT_CHANNEL_NAME = "NBMS notifications"

    /** Creates the channel (HIGH importance). Safe to call as often as you like. */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DEFAULT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(DEFAULT_CHANNEL_ID, DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
