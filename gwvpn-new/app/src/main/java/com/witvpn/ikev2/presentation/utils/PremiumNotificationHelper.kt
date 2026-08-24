package com.witvpn.ikev2.presentation.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.ui.MainActivity

object PremiumNotificationHelper {

    private const val CHANNEL_ID = "premium_notifications"
    private const val CHANNEL_NAME = "Premium"

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun sendActivationNotification(context: Context, endDate: String) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val title = context.getString(R.string.notif_premium_activated_title)
        val body = context.getString(R.string.notif_premium_activated_body, endDate)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(context.getColor(R.color.colorAccent))
            .setContentIntent(getPendingIntent(context))
            .build()

        manager.notify(NOTIFICATION_ID_ACTIVATION, notification)
    }

    fun sendExpirationReminder(context: Context, daysLeft: Int, endDate: String) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val (title, body) = when (daysLeft) {
            7 -> Pair(
                context.getString(R.string.notif_expiration_7_title),
                context.getString(R.string.notif_expiration_7_body, endDate)
            )
            3 -> Pair(
                context.getString(R.string.notif_expiration_3_title),
                context.getString(R.string.notif_expiration_3_body, endDate)
            )
            1 -> Pair(
                context.getString(R.string.notif_expiration_1_title),
                context.getString(R.string.notif_expiration_1_body, endDate)
            )
            else -> return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(context.getColor(R.color.colorAccent))
            .setContentIntent(getPendingIntent(context))
            .build()

        manager.notify(NOTIFICATION_ID_ACTIVATION + daysLeft, notification)
    }

    private const val NOTIFICATION_ID_ACTIVATION = 1001
}
