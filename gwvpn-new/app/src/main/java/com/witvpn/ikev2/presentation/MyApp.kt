package com.witvpn.ikev2.presentation

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
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
        installCrashCapture()
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

    /**
     * Пишет стек любого необработанного исключения в files/last_crash.txt —
     * без этого «вылетает сразу» на устройстве пользователя не диагностировать.
     */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val gwState = runCatching {
                    "gw.state=${com.witvpn.gw.GwManager.state} " +
                        "reconnecting=${com.witvpn.gw.GwManager.isReconnecting} " +
                        "stopping=${com.witvpn.gw.GwManager.isStopping}"
                }.getOrDefault("")
                java.io.File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        append("time=").append(System.currentTimeMillis()).append('\n')
                        append("thread=").append(thread.name).append('\n')
                        append(gwState).append('\n')
                        append(throwable.stackTraceToString())
                        var cause = throwable.cause
                        while (cause != null) {
                            append("\ncaused by:\n").append(cause.stackTraceToString())
                            cause = cause.cause
                        }
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
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
                // Billing 7.x: enablePendingPurchases() без параметров удалён —
                // обязателен PendingPurchasesParams (включая покупки в ожидании).
                val pendingParams = PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
                BillingClient.newBuilder(this)
                    .setListener { _, purchases -> Timber.i(purchases.toString()) }
                    .enablePendingPurchases(pendingParams)
                    .build()
            } else {
                Timber.w("Google Play Services not available, skipping Billing initialization")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error initializing Google Billing")
        }
    }
}