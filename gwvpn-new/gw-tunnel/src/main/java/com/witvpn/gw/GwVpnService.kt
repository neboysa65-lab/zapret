package com.witvpn.gw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.witvpn.gw.model.GwServerConfig
import com.witvpn.gw.socks.GwSocksServer
import com.witvpn.gw.ssh.GwSshTransport
import hev.htproxy.TProxyService
import java.util.Timer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.timerTask
import kotlin.concurrent.withLock

class GwVpnService : TProxyService() {

    companion object {
        const val ACTION_START = "com.witvpn.gw.START"
        const val ACTION_STOP = "com.witvpn.gw.STOP"
        const val EXTRA_CONFIG = "EXTRA_CONFIG_JSON"
        const val EXTRA_EXCLUDED_APPS = "EXTRA_EXCLUDED_APPS"
        const val EXTRA_ALLOWED_APPS = "EXTRA_ALLOWED_APPS"

        private const val TAG = "GwVpnService"
        private const val CHANNEL_ID = "gw_vpn_service"
        private const val NOTIFICATION_ID = 4711
        private const val MTU = 8500
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val CONNECT_RETRY_DELAY_MS = 4000L
    }

    private var tun: ParcelFileDescriptor? = null
    private var socksServer: GwSocksServer? = null
    private var transport: GwSshTransport? = null
    private var statsTimer: Timer? = null
    private val lifecycleLock = ReentrantLock()

    @Volatile private var stopRequested = false
    private var startThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        when (intent?.action) {
            ACTION_START -> {
                val config = GwManager.configFromIntentExtra(
                    intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                )
                if (config == null || config.ipAddress.isBlank()) {
                    Log.e(TAG, "START without valid config")
                    stopEverything()
                    return START_NOT_STICKY
                }
                GwManager.selectedServer = config
                GwManager.saveConfig(this, config)
                startTunnel(
                    config,
                    intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS),
                    intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS),
                )
            }
            ACTION_STOP -> stopEverything()
            else -> {
                if (GwManager.wasRunning(this)) {
                    GwManager.restoreConfig(this)?.let { restored ->
                        GwManager.selectedServer = restored
                        startTunnel(restored, null, null)
                        return START_STICKY
                    }
                }
                stopEverything()
            }
        }
        return START_STICKY
    }

    private fun startTunnel(config: GwServerConfig, excludedApps: List<String>?, allowedApps: List<String>?) {
        GwManager.setState(GwManager.State.CONNECTING)
        val worker = thread(name = "gw-tunnel-start") {
            stopRequested = false
            lifecycleLock.withLock {
                try {
                    teardownTunnel()

                    // Fail fast and loudly: without libhev-socks5-tunnel.so the
                    // tun device would be established and then nothing would
                    // forward its packets, which looks exactly like "connected
                    // but no internet". Check before spending four SSH retries.
                    if (!TProxyService.isTProxyLoaded) {
                        throw IllegalStateException(
                            "tun2socks native library unavailable: " +
                                (TProxyService.tproxyLoadError ?: "libhev-socks5-tunnel.so not loaded")
                        )
                    }

                    var ssh: GwSshTransport? = null
                    var lastError: Exception? = null
                    for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
                        if (stopRequested) throw java.io.IOException("cancelled")
                        try {
                            val candidate = GwSshTransport(config)
                            candidate.connect { socket -> runCatching { protect(socket) } }
                            ssh = candidate
                            transport = candidate
                            break
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "GW connect attempt $attempt/$MAX_CONNECT_ATTEMPTS failed: ${e.message}")
                            runCatching { transport?.close() }
                            transport = null
                            if (stopRequested) throw java.io.IOException("cancelled")
                            if (attempt < MAX_CONNECT_ATTEMPTS) {
                                Thread.sleep(CONNECT_RETRY_DELAY_MS * attempt)
                            }
                        }
                    }

                    val ready = ssh ?: throw lastError ?: IllegalStateException("connect failed")

                    val socks = GwSocksServer(ready)
                    socks.start()
                    socksServer = socks

                    val pfd = establishTun(excludedApps ?: emptyList(), allowedApps ?: emptyList())
                        ?: throw IllegalStateException("TUN establish failed")
                    tun = pfd

                    val yaml = GwManager.tunYamlFile(this, socks.port)
                    TProxyStartService(yaml.absolutePath, pfd.fd)

                    startStatsTimer()
                    GwManager.setState(GwManager.State.CONNECTED)
                    GwManager.selectedServer?.let { GwManager.notifyConnected(it) }
                } catch (t: Throwable) {
                    // Throwable, not Exception: a missing/mismatched .so raises
                    // UnsatisfiedLinkError, which is an Error. Catching only
                    // Exception let it kill this thread silently — no log line
                    // and no state transition, so the UI span forever on
                    // CONNECTING. That was the actual GW "no logs" bug.
                    Log.e(TAG, "GW tunnel failed", t)
                    teardownTunnel()
                    val userError = if (stopRequested) null else (t.message ?: "connection error")
                    GwManager.setState(GwManager.State.DISCONNECTED, userError)
                }
            }
        }
        startThread = worker
    }

    private fun establishTun(excludedApps: List<String>, allowedApps: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("GW VPN")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .allowFamily(android.system.OsConstants.AF_INET)

        val effectiveAllowed = allowedApps.filter { it != packageName }
        if (effectiveAllowed.isNotEmpty()) {
            for (pkg in effectiveAllowed) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: Exception) {
                }
            }
        } else {
            builder.addDisallowedApplication(packageName)
            for (pkg in excludedApps) {
                if (pkg == packageName) continue
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                }
            }
        }
        return builder.establish()
    }

    private fun stopEverything() {
        stopRequested = true
        runCatching { transport?.close() }
        runCatching { startThread?.interrupt() }
        thread(name = "gw-tunnel-stop") {
            lifecycleLock.withLock {
                GwManager.clearRunning(this@GwVpnService)
                teardownTunnel()
                GwManager.setState(GwManager.State.DISCONNECTED)
                stopSelf()
            }
        }
    }

    private fun teardownTunnel() {
        stopStatsTimer()
        runCatching { TProxyStopService() }
        Thread.sleep(200)
        runCatching { socksServer?.stop() }
        runCatching { transport?.close() }
        socksServer = null
        transport = null
        runCatching { tun?.close() }
        tun = null
        GwManager.resetTraffic()
    }

    private fun startStatsTimer() {
        stopStatsTimer()
        statsTimer = Timer("gw-stats", true)
        statsTimer?.scheduleAtFixedRate(timerTask {
            if (!lifecycleLock.tryLock()) return@timerTask
            try {
                val stats = TProxyGetStats()
                if (stats.size >= 4) {
                    GwManager.updateTraffic(up = stats[1], down = stats[3])
                }
            } catch (_: Throwable) {
            } finally {
                lifecycleLock.unlock()
            }
        }, 1000L, 1000L)
    }

    private fun stopStatsTimer() {
        statsTimer?.cancel()
        statsTimer = null
    }

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "GW VPN Service", NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = openIntent?.let {
                PendingIntent.getActivity(
                    this, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }
            val notification = notificationBuilder
                .setContentTitle("GW VPN")
                .setContentText("Connected via GW")
                .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "notification failed", e)
        }
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        lifecycleLock.withLock { runCatching { teardownTunnel() } }
        super.onDestroy()
    }
}
