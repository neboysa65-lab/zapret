package com.witvpn.ikev2.presentation.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.witvpn.ikev2.R

object NotificationHelper {

    fun Context.sendNotification(notification: Notification) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.main_notif_channel_id),
                getString(R.string.main_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(0, notification)
            } else {
                notificationManager.notify(0, notification)
            }
        }
    }
    fun sendNotification(context: Context, title: String, content: String) {
        context.sendNotification(
            NotificationCompat.Builder(context, context.getString(R.string.main_notif_channel_id))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .build()
        )
    }
}