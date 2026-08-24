package com.witvpn.ikev2.presentation.utils

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.witvpn.ikev2.BuildConfig
import timber.log.Timber

private typealias CheckUpdateResult = (appUpdateInfo: AppUpdateInfo) -> Unit

object InAppUpdateHelper {
    private fun checkUpdatesViaGPS(context: Context, onResult: CheckUpdateResult) {
        val appUpdateManager = AppUpdateManagerFactory.create(context)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            onResult.invoke(appUpdateInfo)
        }
    }

    private fun requestUpdateViaGPS(activity: Activity, appUpdateInfo: AppUpdateInfo) {
        val appUpdateManager = AppUpdateManagerFactory.create(activity)
        appUpdateManager.startUpdateFlow(
            appUpdateInfo,
            activity,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE)
                .build(),
        )
    }

    fun checkAndRequestAppUpdate(activity: Activity) {
        if (!BuildConfig.CHECK_IN_APP_UPDATES) return
        if (GooglePlayHelper.deviceHasGooglePlayServices(activity)) {
            checkUpdatesViaGPS(activity) { appUpdateInfo ->
                Timber.d(appUpdateInfo.updateAvailability().toString())
                val needToUpdate = when(appUpdateInfo.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE -> {
                        true
                    }
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        false
                    }
                    UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                        false
                    }
                    else -> {
                        false
                    }
                }
                if (needToUpdate) {
                    requestUpdateViaGPS(activity, appUpdateInfo)
                }
            }
        }
    }
}