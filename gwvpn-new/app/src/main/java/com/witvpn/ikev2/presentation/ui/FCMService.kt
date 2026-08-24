package com.witvpn.ikev2.presentation.ui

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.utils.NotificationHelper

class FCMService: FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        message.notification?.let { notification ->
            NotificationHelper.sendNotification(this, notification.title ?: getString(R.string.app_name), notification.body ?: "")
        }
        super.onMessageReceived(message)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }
}