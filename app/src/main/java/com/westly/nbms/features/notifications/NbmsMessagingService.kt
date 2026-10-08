package com.westly.nbms.features.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.westly.nbms.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Extra on the launch intent that carries the Westly-style link of a tapped push. */
const val EXTRA_LINK = "link"

/**
 * Receives Firebase push messages. When the app is open, Android hands the message to this service and
 * it shows the notification itself; when the app is closed, Android shows the message and puts the
 * `link` data on the launch intent, which the bell picks up.
 */
@AndroidEntryPoint
class NbmsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushRegistrar

    override fun onNewToken(token: String) {
        registrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val enabled = try {
            runBlocking { registrar.isEnabled() }
        } catch (e: Exception) {
            true
        }
        if (!enabled) return

        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["body"]
        if (title.isNullOrBlank() && body.isNullOrBlank()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        NotificationChannels.ensure(this)

        val link = message.data[EXTRA_LINK]
        val id = (message.data["notificationId"] ?: message.messageId ?: System.nanoTime().toString()).hashCode()

        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!link.isNullOrBlank()) putExtra(EXTRA_LINK, link)
        }
        val tap = launch?.let {
            PendingIntent.getActivity(this, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, NotificationChannels.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title ?: "NBMS")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()

        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission was withdrawn a moment ago; nothing to show.
        }
    }
}
