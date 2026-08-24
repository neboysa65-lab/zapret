package io.github.vyomtunnel.sdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.github.vyomtunnel.core.NativeEngine
import io.github.vyomtunnel.sdk.models.ConnectionProfiler
import io.github.vyomtunnel.sdk.models.VyomIpInfo
import io.github.vyomtunnel.sdk.models.VyomProfile
import io.github.vyomtunnel.sdk.utils.AssetUtils
import io.github.vyomtunnel.sdk.utils.LinkParser

object VyomVpnManager {

    private const val TAG = "VyomVpnManager"

    // Broadcast Actions
    const val ACTION_VPN_STATE = "io.github.vyomtunnel.VPN_STATE"
    const val ACTION_VPN_TRAFFIC = "io.github.vyomtunnel.VPN_TRAFFIC"

    // Persistence Keys
    private const val PREFS_NAME = "vyom_vpn_prefs"
    private const val KEY_LAST_CONFIG = "last_config"
    private const val KEY_VPN_ALIVE = "vpn_should_be_running"
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect_on_network"
    private const val KEY_EXCLUDED_APPS = "excluded_apps_list"
    private const val KEY_ALLOWED_APPS = "allowed_apps_list"

    private var isInitialized = false
    private var internalReceiver: BroadcastReceiver? = null
    private var vpnListener: VyomListener? = null
    private var notificationConfig = VyomNotificationConfig()
    private val excludedApps = mutableSetOf<String>()
    private val allowedApps = mutableSetOf<String>()
    private const val KEY_KILL_SWITCH = "kill_switch_enabled"
    const val ACTION_NO_INTERNET = "io.github.vyomtunnel.NO_INTERNET"

    var currentState: VyomState = VyomState.IDLE
        private set

    /**
     * Interface for the host application to receive updates.
     */
    interface VyomListener {
        fun onStateChanged(state: VyomState)
        fun onTrafficUpdate(up: Long, down: Long)
    }

    /**
     * Data class for UI branding of the VPN notification.
     */
    data class VyomNotificationConfig(
        val title: String? = null,
        val content: String? = null,
        val iconResId: Int? = null,
        val channelName: String = "VPN Service"
    )

    // --- CORE API ---

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            AssetUtils.copyAssets(context)
            // NOTE: Native libraries (libxray.so, libhev-socks5-tunnel.so, libvyom-v2ray.so)
            // are intentionally NOT loaded here. They are loaded lazily by NativeEngine's
            // init block when first accessed, which happens inside VyomVpnService that runs
            // in the separate ":xray_process". Loading the 44 MB libxray.so (Go 1.25 runtime)
            // in the main process at app startup causes a native crash on some devices
            // (e.g. Honor 8X / Honor 30i / Kirin 710 + EMUI 9-10) — SIGSEGV inside the Go
            // runtime init cannot be caught by any Java try/catch. Deferring the load to
            // the VPN service process keeps the main app stable.
            loadSavedExclusions(context)
            loadSavedAllowed(context)
            isInitialized = true
            Log.i(TAG, "SDK initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "SDK initialization failed", e)
        }
    }

    fun connect(context: Context, linkOrJson: String): String? {
        return try {
            val finalConfig = if (linkOrJson.trim().startsWith("{")) {
                linkOrJson
            } else {
                LinkParser.parse(linkOrJson)
            }

            // Note: validateConfig is skipped here to avoid ANR on main thread.
            // Validation will happen inside NativeEngine.startXray in the background thread.
            start(context, finalConfig)
            null // Success
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    fun connectWithPermission(activity: Activity, input: String) {
        val finalConfig = try {
            if (input.trim().startsWith("{")) input
            else LinkParser.parse(input)
        } catch (e: Exception) {
            Toast.makeText(activity, "Invalid link or JSON", Toast.LENGTH_LONG).show()
            return
        }

        val validationError = validateConfig(activity, finalConfig)
        if (validationError != null) {
            Toast.makeText(activity, validationError, Toast.LENGTH_LONG).show()
            Log.e("VyomVPN", "Config validation failed: $validationError")
            return
        }

        val intent = VpnService.prepare(activity)
        if (intent != null) {
            saveConfig(activity, finalConfig)
            activity.startActivity(
                Intent(activity, VyomPermissionActivity::class.java)
            )
        } else {
            start(activity, finalConfig)
        }
    }

    fun start(context: Context, configJson: String) {
        saveConfig(context, configJson)
        setVpnShouldRun(context, true)

        val intent = Intent(context, VyomVpnService::class.java).apply {
            action = "START_VPN"
            putExtra(VyomVpnService.EXTRA_CONFIG, configJson)
            putExtra(VyomVpnService.NOTIF_TITLE, notificationConfig.title)
            putExtra(VyomVpnService.NOTIF_CONTENT, notificationConfig.content)
            putExtra(VyomVpnService.NOTIF_ICON, notificationConfig.iconResId ?: 0)
            putExtra(VyomVpnService.NOTIF_CHANNEL, notificationConfig.channelName)
            // The service runs in a separate process (:xray_process) where a
            // cached SharedPreferences instance may be stale — always ship the
            // fresh split-tunneling lists with the start command.
            putStringArrayListExtra(
                VyomVpnService.EXTRA_EXCLUDED_APPS,
                ArrayList(getExcludedApps(context))
            )
            putStringArrayListExtra(
                VyomVpnService.EXTRA_ALLOWED_APPS,
                ArrayList(getAllowedApps(context))
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        setVpnShouldRun(context, false)
        val intent = Intent(context, VyomVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            // App may be in background on Android 8+, service not running
            Log.w(TAG, "Cannot stop VPN service: ${e.message}")
        }
    }

    fun validateConfig(context: Context, config: String): String? {
        val assetPath = context.filesDir.absolutePath
        return NativeEngine.validateConfig(config, assetPath)
    }

    // --- LISTENERS & IPC (Inter-Process Communication) ---

    @RequiresApi(Build.VERSION_CODES.O)
    fun registerListener(context: Context, listener: VyomListener) {
        this.vpnListener = listener
        if (internalReceiver != null) return

        internalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_VPN_STATE -> {
                        val stateName = intent.getStringExtra("STATE") ?: return
                        try {
                            currentState = VyomState.valueOf(stateName)
                            vpnListener?.onStateChanged(currentState)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Unknown VPN state: $stateName")
                        }
                    }
                    ACTION_VPN_TRAFFIC -> {
                        val up = intent.getLongExtra("UP", 0L)
                        val down = intent.getLongExtra("DOWN", 0L)
                        vpnListener?.onTrafficUpdate(up, down)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_VPN_STATE)
            addAction(ACTION_VPN_TRAFFIC)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        context.registerReceiver(internalReceiver, filter, flags)
    }

    fun unregisterListener(context: Context) {
        internalReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered")
            }
            internalReceiver = null
        }
        vpnListener = null
    }

    // --- APP SELECTION (SPLIT TUNNELING) ---

    fun toggleAppExclusion(context: Context, packageName: String) {
        if (excludedApps.contains(packageName)) excludedApps.remove(packageName)
        else excludedApps.add(packageName)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_EXCLUDED_APPS, HashSet(excludedApps)).apply()
    }

    /**
     * Replaces the whole exclusion (split tunneling) set. The host app calls
     * this right before connecting so the SDK always works with the user's
     * latest choice.
     */
    fun setExcludedApps(context: Context, packages: Set<String>) {
        excludedApps.clear()
        excludedApps.addAll(packages)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_EXCLUDED_APPS, HashSet(excludedApps)).apply()
    }

    fun getExcludedApps(context: Context): Set<String> {
        if (excludedApps.isEmpty()) loadSavedExclusions(context)
        return excludedApps
    }

    private fun loadSavedExclusions(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
        excludedApps.clear()
        excludedApps.addAll(saved)
    }

    /**
     * Replaces the allow list ("Only selected" split tunneling mode). When the
     * set is non-empty the VPN routes ONLY these applications through the
     * tunnel; everything else connects directly. An empty set disables the
     * allow-list mode. The host app calls this right before connecting so the
     * SDK always works with the user's latest choice.
     */
    fun setAllowedApps(context: Context, packages: Set<String>) {
        allowedApps.clear()
        allowedApps.addAll(packages)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ALLOWED_APPS, HashSet(allowedApps)).apply()
    }

    fun getAllowedApps(context: Context): Set<String> {
        if (allowedApps.isEmpty()) loadSavedAllowed(context)
        return allowedApps
    }

    private fun loadSavedAllowed(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
        allowedApps.clear()
        allowedApps.addAll(saved)
    }

    // --- DIAGNOSTICS & HELPERS ---

    fun checkInternet(callback: (Boolean) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val conn = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                callback(conn.responseCode == 204)
            } catch (e: Exception) {
                callback(false)
            }
        }
    }

    fun getPerformanceProfile(callback: (VyomProfile) -> Unit) {
        ConnectionProfiler.runDiagnostics(callback = callback)
    }

    fun setNotificationConfig(config: VyomNotificationConfig) {
        this.notificationConfig = config
    }

    // --- PERSISTENCE HELPERS ---

    private fun saveConfig(context: Context, config: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_CONFIG, config).apply()
    }

    private fun setVpnShouldRun(context: Context, shouldRun: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VPN_ALIVE, shouldRun).apply()
    }

    fun getLastConfig(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_CONFIG, null)

    fun wasVpnRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_VPN_ALIVE, false)

    fun setKillSwitch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KILL_SWITCH, enabled).apply()
    }

    fun isKillSwitchEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH, false)
    }

    fun getCoreLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -t 50 VyomXrayCore:I *:S")
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Failed to fetch logs: ${e.message}"
        }
    }

    fun fetchIpInfo(callback: (VyomIpInfo?) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", 20808)
                )

                val url = java.net.URL("https://ipwho.is/")
                val conn = url.openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().use { it.readText() }

                val obj = org.json.JSONObject(response)
                val info = VyomIpInfo(
                    ip = obj.optString("ip", "Unknown"),
                    country = obj.optString("country", "Unknown"),
                    city = obj.optString("city", "Unknown"),
                    isp = obj.optString("connection", "{}").let {
                        org.json.JSONObject(it).optString("isp", "Unknown")
                    }
                )
                callback(info)
            } catch (e: Exception) {
                Log.e("VyomVPN", "Failed to fetch IP info: ${e.message}")
                callback(null)
            }
        }
    }

    fun isPermissionGranted(context: Context): Boolean {
        return android.net.VpnService.prepare(context) == null
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, false)

    fun setAutoReconnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    fun isAutoReconnectEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RECONNECT, false)
}