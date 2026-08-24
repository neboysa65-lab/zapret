package io.github.vyomtunnel.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import hev.htproxy.TProxyService
import io.github.vyomtunnel.core.NativeEngine
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class VyomVpnService : TProxyService() {

    companion object {
        private const val TAG = "VyomVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_MTU = 1280
        private const val LOCAL_ADDRESS = "172.19.0.1"
        private const val BRIDGE_PORT = 20808
        private const val BRIDGE_ADDRESS = "127.0.0.1"

        // Intent Keys
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_EXCLUDED_APPS = "EXTRA_EXCLUDED_APPS"
        const val EXTRA_ALLOWED_APPS = "EXTRA_ALLOWED_APPS"
        const val NOTIF_TITLE = "NOTIF_TITLE"
        const val NOTIF_CONTENT = "NOTIF_CONTENT"
        const val NOTIF_ICON = "NOTIF_ICON"
        const val NOTIF_CHANNEL = "NOTIF_CHANNEL"
    }

    private var statsTimer: Timer? = null
    private var lastUp: Long = 0
    private var lastDown: Long = 0
    private var tunInterface: ParcelFileDescriptor? = null
    private var healthCheckTimer: Timer? = null
    private var lastTotalRx: Long = 0
    private var noDataCount = 0
    private val tunnelLock = ReentrantLock()

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (!VyomVpnManager.isAutoReconnectEnabled(this@VyomVpnService)) {
                Log.i(TAG, "Auto-reconnect disabled by user")
                return
            }

            if (!VyomVpnManager.wasVpnRunning(this@VyomVpnService)) {
                Log.i(TAG, "VPN not marked alive, skipping reconnect")
                return
            }

            if (VyomVpnManager.currentState != VyomState.CONNECTED) return

            val config = VyomVpnManager.getLastConfig(this@VyomVpnService) ?: return

            Log.i(TAG, "Network changed → restarting Xray")

            NativeEngine.stopXray()
            if (NativeEngine.isLoaded) {
                NativeEngine.startXray(config, filesDir.absolutePath)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(TAG, "Network connection lost")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupForegroundService(intent)

        when (intent?.action) {
            "START_VPN" -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: ""
                // Fresh split-tunneling lists from the caller process; fall back
                // to persisted prefs when absent (e.g. system restart).
                val excludedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS)?.toSet()
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS)?.toSet()
                if (config.isNotEmpty()) {
                    startVpn(config, excludedApps, allowedApps)
                } else {
                    Log.w(TAG, "START_VPN received with empty config")
                    stopSelf()
                }
            }
            "STOP_VPN" -> stopVpn()
            null -> {
                // Service restarted by system (START_STICKY) with null intent.
                // Try to resume from last saved config, or stop gracefully.
                val lastConfig = VyomVpnManager.getLastConfig(this)
                if (lastConfig != null && VyomVpnManager.wasVpnRunning(this)) {
                    Log.i(TAG, "Service restarted by system, resuming VPN")
                    startVpn(lastConfig)
                } else {
                    Log.i(TAG, "Service restarted by system, no config to resume — stopping")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn(
        xrayConfig: String,
        excludedAppsOverride: Set<String>? = null,
        allowedAppsOverride: Set<String>? = null
    ) {
        val assetPath = filesDir.absolutePath
        Log.i("VyomVPN", "=== START VPN ===")
        notifyStatus(VyomState.CONNECTING)

        thread(name = "VyomStartup") {
            try {
                if (!NativeEngine.isLoaded) {
                    Log.e("VyomVPN", "Native libraries not loaded, cannot start VPN")
                    notifyStatus(VyomState.ERROR)
                    return@thread
                }

                NativeEngine.stopXray()
                try { TProxyStopService() } catch (_: UnsatisfiedLinkError) {}
                Thread.sleep(300)

                val result = NativeEngine.startXray(xrayConfig, assetPath)
                Log.i("VyomVPN", "Xray started: $result")
                Thread.sleep(1000)

                val builder = Builder()
                    .setSession("GWVPN")
                    .setMtu(DEFAULT_MTU)
                    .addAddress(LOCAL_ADDRESS, 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .allowFamily(android.system.OsConstants.AF_INET)

                // Per-app routing (Split Tunneling). VpnService.Builder forbids
                // mixing allowed and disallowed applications, so exactly one of
                // the two branches below touches the builder. The lists are
                // filtered to installed packages BEFORE the branch is chosen:
                // if the allow list turned out to be fully stale we must fall
                // back to the exclude branch, otherwise the tunnel would
                // capture every app including our own Xray engine and loop.
                val allowedApps = resolveInstalled(
                    (allowedAppsOverride ?: VyomVpnManager.getAllowedApps(this)) - packageName
                )
                if (allowedApps.isNotEmpty()) {
                    // "Only selected" mode: route ONLY these apps through the
                    // tunnel. Our own package is deliberately NOT in the list —
                    // Xray's traffic to the proxy server must never re-enter
                    // the TUN device.
                    for (pkg in allowedApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                            Log.i("VyomVPN", "SplitTunnel INCLUDE: $pkg")
                        } catch (e: Exception) {
                            Log.w("VyomVPN", "Failed to include $pkg", e)
                        }
                    }
                } else {
                    // Default / "All except selected" mode: everything goes
                    // through the tunnel except our own package (and the
                    // user's bypass list, if any).
                    builder.addDisallowedApplication(packageName)
                    val excludedApps = excludedAppsOverride ?: VyomVpnManager.getExcludedApps(this)
                    for (pkg in excludedApps) {
                        try {
                            if (pkg != packageName) {
                                builder.addDisallowedApplication(pkg)
                                Log.i("VyomVPN", "SplitTunnel EXCLUDE: $pkg")
                            }
                        } catch (e: Exception) {
                            Log.w("VyomVPN", "Failed to exclude $pkg", e)
                        }
                    }
                }

                if (VyomVpnManager.isKillSwitchEnabled(this)) {
                    builder.setBlocking(true)
                }

                tunInterface = builder.establish()
                val fd = tunInterface?.fd ?: throw IllegalStateException("TUN failed")
                Log.i("VyomVPN", "TUN OK FD=$fd")

                val tunFile = File(filesDir, "tun.yaml")
                tunFile.writeText(
                    """
                socks5:
                  address: 127.0.0.1
                  port: 20808
                  udp: udp
                tcp:
                  enabled: true
                udp:
                  enabled: true
                dns:
                  enabled: true
                """.trimIndent()
                )

                TProxyStartService(tunFile.absolutePath, fd)

                startStatsTicker()
                startHealthGuard() // <-- Enable health check
                notifyStatus(VyomState.CONNECTED)
                Log.i("VyomVPN", "=== VPN CONNECTED ===")

            } catch (e: Exception) {
                Log.e("VyomVPN", "VPN START FAILED", e)
                notifyStatus(VyomState.ERROR)
            }
        }
    }

    /**
     * Filters the given package names down to the ones actually installed
     * right now. addAllowedApplication/addDisallowedApplication throw for
     * unknown packages, and for the allow list the failure mode is severe:
     * if not a single allowed app gets registered, Android treats the tunnel
     * as "capture everything", which must never happen implicitly.
     */
    private fun resolveInstalled(packages: Set<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        return packages.filterTo(LinkedHashSet()) { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (e: Exception) {
                Log.w("VyomVPN", "SplitTunnel: package not resolvable, skipping: $pkg")
                false
            }
        }
    }

    private fun stopVpn() {
        notifyStatus(VyomState.STOPPING)
        healthCheckTimer?.cancel()

        thread(start = true, name = "VyomShutdownThread") {
            try {
                stopStatsTicker()

                tunnelLock.lock()
                try {
                    try { TProxyStopService() } catch (_: UnsatisfiedLinkError) {}
                    NativeEngine.stopXray()
                    tunInterface?.close()
                    tunInterface = null
                } finally {
                    tunnelLock.unlock()
                }

                notifyStatus(VyomState.DISCONNECTED)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Vpn stop crash", e)
                notifyStatus(VyomState.DISCONNECTED)
                stopSelf()
            }
        }
    }

    private fun startStatsTicker() {
        statsTimer?.cancel()
        statsTimer = Timer()
        statsTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!tunnelLock.tryLock()) return@run
                try {
                    val stats = this@VyomVpnService.TProxyGetStats()
                    if (stats.size >= 4) {
                        val totalUp = stats[1]
                        val totalDown = stats[3]

                        lastUp = totalUp
                        lastDown = totalDown

                        notifyTraffic(totalUp, totalDown)
                    }
                } catch (_: Exception) {
                } finally {
                    tunnelLock.unlock()
                }
            }
        }, 0L, 1000L)
    }

    private fun stopStatsTicker() {
        statsTimer?.cancel()
        statsTimer = null
        lastUp = 0
        lastDown = 0
    }

    private fun setupForegroundService(intent: Intent?) {
        try {
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            val title = intent?.getStringExtra(NOTIF_TITLE) ?: appLabel
            val content = intent?.getStringExtra(NOTIF_CONTENT) ?: "VPN is active"
            val iconRes = intent?.getIntExtra(NOTIF_ICON, 0)?.takeIf { it != 0 }
                ?: applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_lock_lock
            val channelName = intent?.getStringExtra(NOTIF_CHANNEL) ?: "VPN Service"

            createNotificationChannel(channelName)

            val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }

            // Create PendingIntent to open the app
            val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = if (openAppIntent != null) {
                android.app.PendingIntent.getActivity(
                    this, 0, openAppIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notification = notificationBuilder
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(iconRes)
                .setContentIntent(pendingIntent) // Add click action
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup foreground notification", e)
        }
    }

    private fun createNotificationChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        try {
            stopStatsTicker()
            connectivityManager.unregisterNetworkCallback(networkCallback)
            healthCheckTimer?.cancel()
            try { TProxyStopService() } catch (_: UnsatisfiedLinkError) {}
            tunInterface?.close()
            tunInterface = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun notifyStatus(state: VyomState) {
        val intent = Intent(VyomVpnManager.ACTION_VPN_STATE)
        intent.setPackage(packageName)
        intent.putExtra("STATE", state.name)
        sendBroadcast(intent)
    }

    private fun notifyTraffic(up: Long, down: Long) {
        val intent = Intent(VyomVpnManager.ACTION_VPN_TRAFFIC)
        intent.setPackage(packageName)
        intent.putExtra("UP", up)
        intent.putExtra("DOWN", down)
        sendBroadcast(intent)
    }

    private fun startHealthGuard() {
        healthCheckTimer?.cancel()
        healthCheckTimer = Timer()
        healthCheckTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!tunnelLock.tryLock()) return@run
                try {
                    val stats = this@VyomVpnService.TProxyGetStats()
                    if (stats.size >= 4) {
                        val currentTotalRx = stats[3]

                        if (currentTotalRx > 0 && currentTotalRx == lastTotalRx) {
                            noDataCount++
                        } else {
                            noDataCount = 0
                        }

                        lastTotalRx = currentTotalRx

                        if (noDataCount >= 5) {
                            Log.w(TAG, "No internet traffic detected for 5s! Restarting tunnel...")

                            try {
                                val tunFile = File(filesDir, "tun.yaml")
                                val fd = tunInterface?.fd
                                if (fd != null) {
                                    TProxyStopService()
                                    Thread.sleep(500)
                                    TProxyStartService(tunFile.absolutePath, fd)
                                    Log.i(TAG, "Tunnel restarted successfully")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to restart tunnel", e)
                            }

                            noDataCount = 0
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    tunnelLock.unlock()
                }
            }
        }, 5000L, 1000L)
    }
}