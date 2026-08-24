package com.witvpn.ikev2.presentation.ui.connectlimit

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.ui.MainActivity
import com.witvpn.ikev2.presentation.utils.NotificationHelper
import com.witvpn.ikev2.presentation.utils.NotificationHelper.sendNotification
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.logic.VpnStateService.VpnStateListener
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

class VpnServiceLimitListener(
    private val service: VpnStateService
): VpnStateListener {
    private val sharedPreferences: SharedPreferences = service.applicationContext.getSharedPreferences(
        "TimerLimitationListener",
        Context.MODE_PRIVATE
    )
    companion object {
        val MILLIS_ELAPSED_LIMIT = TimeUnit.MINUTES.toMillis(BuildConfig.CONNECTION_TIME_LIMIT_MINUTES)
    }

    private var isRunnablePaused = true
    private val ticker = Timer().scheduleAtFixedRate(0, 1000) {
        if (sharedPreferences.getLong("elapsedAtDay", 0) != TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
            onTimeElapsedReset()
        if (!isRunnablePaused)
            onEverySecond()
    }
    var elapsedListener: ((Long) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(sharedPreferences.getLong("elapsedMillis", 0))
        }
    val isLimitElapsed: Boolean
        get() = sharedPreferences.getLong("elapsedMillis", 0) >= MILLIS_ELAPSED_LIMIT
    var isEnabled: Boolean = false
        set(value) {
            if (!value && isRunnablePaused) {
                isRunnablePaused = true
            }
            field = value
        }
    init {
        sharedPreferences.getLong("elapsedAtDay", Long.MIN_VALUE).also { elapsedAtDay ->
            if (elapsedAtDay == Long.MIN_VALUE)
                sharedPreferences.edit {
                    putLong("elapsedAtDay", TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
                }
        }
    }
    private fun onConnect() {
        isRunnablePaused = false
    }
    private fun onDisconnect() {
        isRunnablePaused = true
    }
    private fun onEverySecond() {
        sharedPreferences.getLong("elapsedAtDay", 0).also { elapsedAtDay ->
            TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()).also { toDay ->
                if (elapsedAtDay != toDay) {
                    onTimeElapsedReset()
                } else {
                    if (isLimitElapsed) {
                        onTimeElapsed()
                    } else {
                        sharedPreferences.getLong("elapsedMillis", 0).also { elapsedMillis ->
                            (elapsedMillis + 1000).also { newElapsed ->
                                sharedPreferences.edit {
                                    putLong("elapsedMillis", newElapsed)
                                }
                                elapsedListener?.invoke(newElapsed)
                            }
                        }
                    }
                }
            }
        }
    }
    private fun onTimeElapsed() {
        if (ActivityCompat.checkSelfPermission(
                service,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) service.sendNotification(
            NotificationCompat.Builder(service, service.getString(R.string.main_notif_channel_id))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setContentTitle(service.getString(R.string.limit_notification_title))
                .setContentText(service.getString(R.string.limit_notification_content))
                .setAutoCancel(true)
                .build()
        )
        service.disconnect()
    }
    private fun onTimeElapsedReset() {
        sharedPreferences.edit {
            putLong("elapsedAtDay", TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
            putLong("elapsedMillis", 0)
        }
        elapsedListener?.invoke(0)
    }
    override fun stateChanged() {
        if (isEnabled) {
            when(service.state) {
                VpnStateService.State.DISABLED -> onDisconnect()
                VpnStateService.State.CONNECTED -> onConnect()
                VpnStateService.State.DISCONNECTING -> onDisconnect()
                else -> {}
            }
        } else {
            onDisconnect()
        }
    }
}