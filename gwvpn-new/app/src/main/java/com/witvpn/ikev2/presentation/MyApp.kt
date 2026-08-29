package com.witvpn.ikev2.presentation

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.presentation.utils.ConnectionTracker
import com.witvpn.ikev2.presentation.utils.GooglePlayHelper
import com.witvpn.ikev2.presentation.utils.RemoteConfigManager
import com.witvpn.ikev2.presentation.utils.connectivity.ConnectivityProvider
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MyApp : Application() {
    companion object {
        lateinit var self: MyApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        self = this
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Подтягиваем актуальные домены (API / оплата) из Firebase Remote Config.
        // Делать это надо как можно раньше — до первых сетевых запросов Retrofit.
        RemoteConfigManager.init(this)

        if (isMainProcess()) {
            // Ads only ever render from the UI process; initializing them in the VPN
            // service process would waste work and network.
            try {
                CasAds.startLoadAds()
            } catch (e: Throwable) {
                Timber.e(e, "CAS ads init failed")
            }

            ConnectivityProvider.createProvider(this).subscribe()

            // GW-онлайн: сервис может восстановить туннель сам (START_STICKY)
            // без открытого UI — переподписываем heartbeat на месте.
            com.witvpn.gw.GwManager.onTunnelConnected = { config ->
                val userId = getSharedPreferences("myPref", Context.MODE_PRIVATE)
                    .getString(com.witvpn.ikev2.presentation.utils.SharePrefs.KEY_USER_ID, null)
                if (!userId.isNullOrBlank()) {
                    ConnectionTracker.reportConnect(this, userId, config.ipAddress, "gw")
                }
            }
        }

        initGoogleBilling()
    }

    // your package name is the same with your main process name
    private fun isMainProcess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageName == getProcessName()
        } else packageName == getProcessNameLegacy()
    }

    // you can use this method to get current process name, you will get
    private fun getProcessNameLegacy(): String? {
        val mypid = Process.myPid()
        val manager =
            getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = manager.runningAppProcesses
        for (info in infos) {
            if (info.pid == mypid) {
                return info.processName
            }
        }
        // may never return null
        return null
    }

    private fun initGoogleBilling() {
        try {
            if (GooglePlayHelper.deviceHasGooglePlayServices(this)) {
                BillingClient.newBuilder(this)
                    .setListener { _, purchases -> Timber.i(purchases.toString()) }
                    .enablePendingPurchases()
                    .build()
            } else {
                Timber.w("Google Play Services not available, skipping Billing initialization")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error initializing Google Billing")
        }
    }
}